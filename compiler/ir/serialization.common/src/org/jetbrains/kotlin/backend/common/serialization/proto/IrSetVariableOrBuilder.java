// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: compiler/ir/serialization.common/src/KotlinIr.proto

package org.jetbrains.kotlin.backend.common.serialization.proto;

public interface IrSetVariableOrBuilder extends
    // @@protoc_insertion_point(interface_extends:org.jetbrains.kotlin.backend.common.serialization.proto.IrSetVariable)
    org.jetbrains.kotlin.protobuf.MessageLiteOrBuilder {

  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrSymbol symbol = 1;</code>
   */
  boolean hasSymbol();
  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrSymbol symbol = 1;</code>
   */
  org.jetbrains.kotlin.backend.common.serialization.proto.IrSymbol getSymbol();

  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression value = 2;</code>
   */
  boolean hasValue();
  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression value = 2;</code>
   */
  org.jetbrains.kotlin.backend.common.serialization.proto.IrExpression getValue();

  /**
   * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatementOrigin origin = 3;</code>
   */
  boolean hasOrigin();
  /**
   * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrStatementOrigin origin = 3;</code>
   */
  org.jetbrains.kotlin.backend.common.serialization.proto.IrStatementOrigin getOrigin();
}