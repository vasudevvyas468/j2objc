/*
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.j2objc.translate;

import com.google.common.collect.Lists;
import com.google.devtools.j2objc.types.GeneratedMethodBinding;
import com.google.devtools.j2objc.types.NodeCopier;
import com.google.devtools.j2objc.types.Types;
import com.google.devtools.j2objc.util.ASTUtil;
import com.google.devtools.j2objc.util.BindingUtil;
import com.google.devtools.j2objc.util.ErrorReportingASTVisitor;
import com.google.devtools.j2objc.util.NameTable;
import com.google.devtools.j2objc.util.UnicodeUtils;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.Iterator;
import java.util.List;

/**
 * Modifies initializers to be more iOS like.  Static initializers are
 * combined into a static initialize method, instance initializer
 * statements are injected into constructors.  If a class doesn't have
 * any constructors but does have instance initialization statements,
 * a default constructor is added to run them.
 *
 * @author Tom Ball
 */
public class InitializationNormalizer extends ErrorReportingASTVisitor {

  @Override
  public void endVisit(TypeDeclaration node) {
    normalizeMembers(node);
    super.endVisit(node);
  }

  @Override
  public void endVisit(EnumDeclaration node) {
    normalizeMembers(node);
    super.endVisit(node);
  }

  @Override
  public void endVisit(AnnotationTypeDeclaration node) {
    normalizeMembers(node);
    super.endVisit(node);
  }


  void normalizeMembers(AbstractTypeDeclaration node) {
    List<Statement> initStatements = Lists.newArrayList();
    List<Statement> classInitStatements = Lists.newArrayList();
    List<MethodDeclaration> methods = Lists.newArrayList();
    ITypeBinding binding = Types.getTypeBinding(node);

    // Scan class, gathering initialization statements in declaration order.
    List<BodyDeclaration> members = ASTUtil.getBodyDeclarations(node);
    Iterator<BodyDeclaration> iterator = members.iterator();
    while (iterator.hasNext()) {
      BodyDeclaration member = iterator.next();
      switch (member.getNodeType()) {
        case ASTNode.ENUM_DECLARATION:
        case ASTNode.TYPE_DECLARATION:
          normalizeMembers((AbstractTypeDeclaration) member);
          break;
        case ASTNode.METHOD_DECLARATION:
          methods.add((MethodDeclaration) member);
          break;
        case ASTNode.INITIALIZER:
          addInitializer(member, initStatements, classInitStatements);
          iterator.remove();
          break;
        case ASTNode.FIELD_DECLARATION:
          addFieldInitializer(member, binding.isInterface(), initStatements, classInitStatements);
          break;
      }
    }

    // Update any primary constructors with init statements.
    if (!binding.isInterface()) {
      boolean needsConstructor = true;
      for (MethodDeclaration md : methods) {
        if (md.isConstructor()) {
          needsConstructor = false;
        }
        normalizeMethod(md, initStatements);
      }
      if (needsConstructor) {
        addDefaultConstructor(binding, members, initStatements, node.getAST());
      }
    }

    // Create an initialize method, if necessary.
    if (!classInitStatements.isEmpty()) {
      addClassInitializer(binding, members, classInitStatements, node.getAST());
    }
  }

  /**
   * Add a static or instance init block's statements to the appropriate list
   * of initialization statements.
   */
  private void addInitializer(BodyDeclaration member, List<Statement> initStatements,
      List<Statement> classInitStatements) {
    Initializer initializer = (Initializer) member;
    List<Statement> l =
        Modifier.isStatic(initializer.getModifiers()) ? classInitStatements : initStatements;
    l.add(initializer.getBody());
  }

  /**
   * Strip field initializers, convert them to assignment statements, and
   * add them to the appropriate list of initialization statements.
   */
  private void addFieldInitializer(
      BodyDeclaration member, boolean isInterface, List<Statement> initStatements,
      List<Statement> classInitStatements) {
    FieldDeclaration field = (FieldDeclaration) member;
    for (VariableDeclarationFragment frag : ASTUtil.getFragments(field)) {
      if (frag.getInitializer() != null) {
        Statement assignStmt = makeAssignmentStatement(frag);
        if (Modifier.isStatic(field.getModifiers()) || isInterface) {
          if (requiresInitializer(frag)) {
            classInitStatements.add(assignStmt);
            frag.setInitializer(null);
          }
        } else {
          // always initialize instance variables, since they can't be constants
          initStatements.add(assignStmt);
          frag.setInitializer(null);
        }
      }
    }
  }

  /**
   * Determines if a variable declaration requires initialization. (ie. cannot
   * be assigned to a literal value in ObjC.
   */
  private boolean requiresInitializer(VariableDeclarationFragment frag) {
    Expression initializer = frag.getInitializer();
    switch (initializer.getNodeType()) {
      case ASTNode.BOOLEAN_LITERAL:
      case ASTNode.CHARACTER_LITERAL:
      case ASTNode.NULL_LITERAL:
      case ASTNode.NUMBER_LITERAL:
        return false;
      case ASTNode.STRING_LITERAL:
        return !UnicodeUtils.hasValidCppCharacters(((StringLiteral) initializer).getLiteralValue());
    }
    if (BindingUtil.isPrimitiveConstant(Types.getVariableBinding(frag))) {
      return false;
    }
    // If the initializer is not a literal, but has a constant value, convert it
    // to a literal. (as javac would do)
    Object constantValue = initializer.resolveConstantExpressionValue();
    if (constantValue != null) {
      if (constantValue instanceof String
          && !UnicodeUtils.hasValidCppCharacters((String) constantValue)) {
        return true;
      }
      frag.setInitializer(ASTFactory.makeLiteral(
          frag.getAST(), constantValue, Types.getTypeBinding(frag)));
      return false;
    }
    return true;
  }

  private ExpressionStatement makeAssignmentStatement(VariableDeclarationFragment fragment) {
    AST ast = fragment.getAST();
    return ast.newExpressionStatement(ASTFactory.newAssignment(
        ast, ASTFactory.newSimpleName(ast, Types.getVariableBinding(fragment)),
        NodeCopier.copySubtree(ast, fragment.getInitializer())));
  }

  /**
   * Insert initialization statements into "primary" constructors.  A
   * "primary" construction is defined here as a constructor that doesn't
   * call other constructors in this class, and is similar in concept to
   * Objective-C's "designated initializers."
   *
   * @return true if constructor was normalized
   */
  void normalizeMethod(MethodDeclaration node, List<Statement> initStatements) {
    if (isDesignatedConstructor(node)) {
      AST ast = node.getAST();
      List<Statement> stmts = ASTUtil.getStatements(node.getBody());
      int superCallIdx = findSuperConstructorInvocation(stmts);

      // Insert initializer statements after the super invocation. If there
      // isn't a super invocation, add one (like all Java compilers do).
      if (superCallIdx == -1) {
        ITypeBinding superType = Types.getTypeBinding(node).getSuperclass();
        GeneratedMethodBinding newBinding = GeneratedMethodBinding.newConstructor(
            superType, Modifier.PUBLIC);
        stmts.add(0, ASTFactory.newSuperConstructorInvocation(ast, newBinding));
        superCallIdx = 0;
      }

      List<Statement> unparentedStmts = NodeCopier.copySubtrees(ast, initStatements);
      stmts.addAll(superCallIdx + 1, unparentedStmts);
    }
  }

  private int findSuperConstructorInvocation(List<Statement> statements) {
    for (int i = 0; i < statements.size(); i++) {
      if (statements.get(i) instanceof SuperConstructorInvocation) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns true if this is a constructor that doesn't doesn't call
   * "this(...)".  This constructors are skipped so initializers
   * aren't run more than once per instance creation.
   */
  boolean isDesignatedConstructor(MethodDeclaration node) {
    if (!node.isConstructor()) {
      return false;
    }
    Block body = node.getBody();
    if (body == null) {
      return false;
    }
    List<Statement> stmts = ASTUtil.getStatements(body);
    if (stmts.isEmpty()) {
      return true;
    }
    Statement firstStmt = stmts.get(0);
    return !(firstStmt instanceof ConstructorInvocation);
  }

  void addDefaultConstructor(ITypeBinding type, List<BodyDeclaration> members,
      List<Statement> initStatements, AST ast) {
    SuperConstructorInvocation superCall = ast.newSuperConstructorInvocation();
    int constructorModifier =
        type.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
    GeneratedMethodBinding binding = GeneratedMethodBinding.newConstructor(
        type.getSuperclass(), constructorModifier);
    Types.addBinding(superCall, binding);
    initStatements.add(0, superCall);
    members.add(createMethod(ast, GeneratedMethodBinding.newConstructor(type, constructorModifier),
                             initStatements));
  }

  void addClassInitializer(ITypeBinding type, List<BodyDeclaration> members,
      List<Statement> classInitStatements, AST ast) {
    int modifiers = Modifier.PUBLIC | Modifier.STATIC;
    members.add(createMethod(ast, GeneratedMethodBinding.newMethod(NameTable.CLINIT_NAME, modifiers,
        ast.resolveWellKnownType("void"), type), classInitStatements));
  }

  private MethodDeclaration createMethod(
      AST ast, IMethodBinding binding, List<Statement> statements) {
    Block body = ast.newBlock();
    List<Statement> stmts = ASTUtil.getStatements(body);
    for (Statement stmt : statements) {
      Statement newStmt = NodeCopier.copySubtree(ast, stmt);
      stmts.add(newStmt);
    }
    MethodDeclaration method = ASTFactory.newMethodDeclaration(ast, binding);
    method.setBody(body);
    return method;
  }
}
