package proguard.evaluation.value.object.model;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import proguard.classfile.ClassConstants;
import proguard.classfile.Clazz;
import proguard.classfile.util.ClassUtil;
import proguard.evaluation.MethodResult;
import proguard.evaluation.ValueCalculator;
import proguard.evaluation.executor.MethodExecutionInfo;
import proguard.evaluation.value.object.model.reflective.ModelHelper;
import proguard.evaluation.value.object.model.reflective.ModeledInstanceMethod;
import proguard.evaluation.value.object.model.reflective.ReflectiveModel;

/** A {@link Model} to track specific Clazz constants. */
public class ClassModel implements ReflectiveModel<ClassModel> {

  private final Clazz clazz;

  /**
   * Mandatory no-argument constructor.
   *
   * @see ModelHelper#getDummyObject(Class)
   */
  private ClassModel() {
    this.clazz = null;
  }

  public ClassModel(Clazz clazz) {
    this.clazz = clazz;
  }

  public Clazz getClazz() {
    return clazz;
  }

  // Model implementation.

  @NotNull
  @Override
  public String getType() {
    return ClassConstants.TYPE_JAVA_LANG_CLASS;
  }

  @Override
  public MethodResult init(
      MethodExecutionInfo methodExecutionInfo, ValueCalculator valueCalculator) {
    throw new UnsupportedOperationException(
        "Constructors invocation is not supported in ClassModel");
  }

  @Override
  public MethodResult invokeStatic(
      MethodExecutionInfo methodExecutionInfo, ValueCalculator valueCalculator) {
    throw new UnsupportedOperationException(
        "Static method invocation is not supported in ClassModel");
  }

  // Supported method implementations.

  /** Models {@link Class#getName()}. */
  @ModeledInstanceMethod(name = "getName", descriptor = "()Ljava/lang/String;")
  MethodResult getName(ModelHelper.MethodExecutionContext context) {
    if (clazz == null) return MethodResult.invalidResult();
    return ModelHelper.createDefaultReturnResult(context, clazz.getName());
  }

  /** Models {@link Class#getSimpleName()}. */
  @ModeledInstanceMethod(name = "getSimpleName", descriptor = "()Ljava/lang/String;")
  MethodResult getSimpleName(ModelHelper.MethodExecutionContext context) {
    if (clazz == null) return MethodResult.invalidResult();
    return ModelHelper.createDefaultReturnResult(
        context, ClassUtil.internalSimpleClassName(clazz.getName()));
  }

  /** Models {@link Class#getCanonicalName()}. */
  @ModeledInstanceMethod(name = "getCanonicalName", descriptor = "()Ljava/lang/String;")
  MethodResult getCanonicalName(ModelHelper.MethodExecutionContext context) {
    if (clazz == null) return MethodResult.invalidResult();
    return ModelHelper.createDefaultReturnResult(
        context, ClassUtil.canonicalClassName(clazz.getName()));
  }

  /** Models {@link Class#getPackageName()}. */
  @ModeledInstanceMethod(name = "getPackageName", descriptor = "()Ljava/lang/String;")
  MethodResult getPackageName(ModelHelper.MethodExecutionContext context) {
    if (clazz == null) return MethodResult.invalidResult();
    return ModelHelper.createDefaultReturnResult(
        context, ClassUtil.externalPackageName(clazz.getName()));
  }

  /** Models {@link Class#getTypeName()}. */
  @ModeledInstanceMethod(name = "getTypeName", descriptor = "()Ljava/lang/String;")
  MethodResult getTypeName(ModelHelper.MethodExecutionContext context) {
    if (clazz == null) return MethodResult.invalidResult();
    return ModelHelper.createDefaultReturnResult(
        context, ClassUtil.externalClassName(clazz.getName()));
  }

  /** Models {@link Class#getSuperclass()}. */
  @ModeledInstanceMethod(name = "getSuperclass", descriptor = "()Ljava/lang/Class;")
  private MethodResult getSuperclass(ModelHelper.MethodExecutionContext context) {
    if (clazz == null) return MethodResult.invalidResult();
    Clazz superClass = clazz.getSuperClass();
    if (superClass == null) return MethodResult.invalidResult();
    return ModelHelper.createDefaultReturnResult(context, new ClassModel(clazz.getSuperClass()));
  }

  // Object overrides.

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ClassModel that = (ClassModel) o;
    return Objects.equals(clazz, that.clazz);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(clazz);
  }

  @Override
  public String toString() {
    return String.format("ClassModel{%s}", clazz.getName());
  }
}
