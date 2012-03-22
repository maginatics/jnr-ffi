package jnr.ffi.provider.jffi;

import com.kenai.jffi.*;
import com.kenai.jffi.CallingConvention;
import com.kenai.jffi.Platform;
import jnr.ffi.*;
import jnr.ffi.NativeType;
import jnr.ffi.Struct;
import org.objectweb.asm.Label;

import java.util.concurrent.atomic.AtomicLong;

import static jnr.ffi.provider.jffi.AsmUtil.*;
import static jnr.ffi.provider.jffi.BaseMethodGenerator.loadAndConvertParameter;
import static jnr.ffi.provider.jffi.CodegenUtils.*;
import static jnr.ffi.provider.jffi.CodegenUtils.p;
import static jnr.ffi.provider.jffi.CodegenUtils.sig;
import static jnr.ffi.provider.jffi.NumberUtil.*;
import static jnr.ffi.provider.jffi.NumberUtil.sizeof;
import static org.objectweb.asm.Opcodes.*;

/**
 *
 */
class X86MethodGenerator implements MethodGenerator {
    private final AtomicLong nextMethodID = new AtomicLong(0);
    private final StubCompiler compiler;
    private final BufferMethodGenerator bufgen;

    X86MethodGenerator(StubCompiler compiler, BufferMethodGenerator bufgen) {
        this.compiler = compiler;
        this.bufgen = bufgen;
    }

    public boolean isSupported(ResultType resultType, ParameterType[] parameterTypes, CallingConvention callingConvention) {
        if (!Boolean.valueOf(System.getProperty("jnr.ffi.x86asm.enabled", "true"))) {
            return false;
        }

        final Platform platform = Platform.getPlatform();

        if (platform.getOS().equals(Platform.OS.WINDOWS)) {
            return false;
        }

        if (!platform.getCPU().equals(Platform.CPU.I386) && !platform.getCPU().equals(Platform.CPU.X86_64)) {
            return false;
        }

        if (!callingConvention.equals(CallingConvention.DEFAULT)) {
            return false;
        }

        int objectCount = 0;
        Class[] nativeParameterTypes = new Class[parameterTypes.length];
        for (int i = 0; i < nativeParameterTypes.length; ++i) {
            if (!isSupportedParameter(parameterTypes[i])) {
                return false;
            }

            if (isSupportedObjectParameterType(parameterTypes[i])) {
                objectCount++;
            }

            nativeParameterTypes[i] = nativeParameterType(parameterTypes[i]);
        }

        if (objectCount > 0) {
            if (parameterTypes.length > 4 || objectCount > 3) {
                return false;
            }
        }

        return isSupportedResult(resultType)
                && compiler.canCompile(nativeResultType(resultType), nativeParameterTypes, callingConvention);
    }

    public void generate(AsmBuilder builder, String functionName, Function function,
                         ResultType resultType, ParameterType[] parameterTypes, boolean ignoreError) {

        Class[] nativeParameterTypes = new Class[parameterTypes.length];
        boolean wrapperNeeded = false;
        for (int i = 0; i < parameterTypes.length; ++i) {

            nativeParameterTypes[i] = nativeParameterType(parameterTypes[i]);
            wrapperNeeded |= nativeParameterTypes[i] != parameterTypes[i].effectiveJavaType();
            wrapperNeeded |= isSupportedObjectParameterType(parameterTypes[i]);
        }

        Class nativeReturnType = nativeResultType(resultType);
        wrapperNeeded |= nativeReturnType != resultType.effectiveJavaType();

        String stubName = functionName + (wrapperNeeded ? "$jni$" + nextMethodID.incrementAndGet() : "");

        builder.getClassVisitor().visitMethod(ACC_PUBLIC | ACC_FINAL | ACC_NATIVE | (wrapperNeeded ? ACC_STATIC : 0),
                stubName, sig(nativeReturnType, nativeParameterTypes), null, null);

        compiler.compile(function, stubName, nativeReturnType, nativeParameterTypes,
                CallingConvention.DEFAULT, !ignoreError);

        // If unboxing of parameters is required, generate a wrapper
        if (wrapperNeeded) {
            generateWrapper(builder, functionName, function, resultType, parameterTypes, ignoreError,
                    stubName, nativeReturnType, nativeParameterTypes);
        }
    }

    private static void generateWrapper(AsmBuilder builder, String functionName, Function function,
                                        ResultType resultType, ParameterType[] parameterTypes, boolean ignoreError,
                                        String nativeMethodName, Class nativeReturnType, Class[] nativeParameterTypes) {
        Class[] javaParameterTypes = new Class[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            javaParameterTypes[i] = parameterTypes[i].getDeclaredType();
        }
        SkinnyMethodAdapter mv = new SkinnyMethodAdapter(builder.getClassVisitor().visitMethod(
                ACC_PUBLIC | ACC_FINAL,
                functionName, sig(resultType.getDeclaredType(), javaParameterTypes), null, null));
        mv = new SkinnyMethodAdapter(AsmUtil.newTraceMethodVisitor(mv));
        mv.start();


        LocalVariableAllocator localVariableAllocator = new LocalVariableAllocator(parameterTypes);
        final LocalVariable objCount = localVariableAllocator.allocate(int.class);
        LocalVariable[] parameters = AsmUtil.getParameterVariables(parameterTypes);
        LocalVariable[] pointers = new LocalVariable[parameterTypes.length];
        LocalVariable[] strategies = new LocalVariable[parameterTypes.length];
        int pointerCount = 0;

        for (int i = 0; i < parameterTypes.length; ++i) {
            Class javaParameterType = parameterTypes[i].effectiveJavaType();
            Class nativeParameterType = nativeParameterTypes[i];

            loadAndConvertParameter(builder, mv, parameters[i], parameterTypes[i]);

            if (Number.class.isAssignableFrom(javaParameterType)) {
                unboxNumber(mv, javaParameterType, nativeParameterType);

            } else if (Boolean.class.isAssignableFrom(javaParameterType)) {
                unboxBoolean(mv, javaParameterType, nativeParameterType);

            } else if (Pointer.class.isAssignableFrom(javaParameterType) && isDelegate(parameterTypes[i].getDeclaredType())) {
                // delegates are always direct, so handle without the strategy processing
                unboxPointer(mv, nativeParameterType);

            } else if (long.class == javaParameterType) {
                narrow(mv, long.class, nativeParameterType);

            } else if (Pointer.class.isAssignableFrom(javaParameterType)
                    || Struct.class.isAssignableFrom(javaParameterType)
                    ) {

                // Initialize the objectCount local var
                if (pointerCount++ < 1) {
                    mv.pushInt(0);
                    mv.istore(objCount);
                }

                strategies[i] = localVariableAllocator.allocate(ObjectParameterStrategy.class);

                if (parameterTypes[i].toNativeConverter != null) {
                    // Save the current pointer parameter (result of type conversion above)
                    pointers[i] = localVariableAllocator.allocate(Object.class);
                    mv.astore(pointers[i]);
                    mv.aload(pointers[i]);
                } else {
                    // avoid the save/load of an extra local var if no parameter conversion took place
                    pointers[i] = parameters[i];
                }

                AbstractFastNumericMethodGenerator.emitPointerParameterStrategyLookup(mv, javaParameterType, parameterTypes[i].annotations);

                mv.astore(strategies[i]);
                mv.aload(strategies[i]);

                mv.getfield(p(PointerParameterStrategy.class), "objectCount", ci(int.class));
                mv.iload(objCount);
                mv.iadd();
                mv.istore(objCount);

                // Get the native address (will return zero for heap objects)
                mv.aload(strategies[i]);
                mv.aload(pointers[i]);
                mv.invokevirtual(PointerParameterStrategy.class, "address", long.class, Object.class);
                narrow(mv, long.class, nativeParameterType);

            } else if (!javaParameterType.isPrimitive()) {
                throw new IllegalArgumentException("unsupported type " + javaParameterType);
            }
        }
        Label hasObjects = new Label();
        Label convertResult = new Label();

        // If there are any objects, jump to the fast-object path
        if (pointerCount > 0) {
            mv.iload(objCount);
            mv.ifne(hasObjects);
        }

        // invoke the compiled stub
        mv.invokestatic(builder.getClassNamePath(), nativeMethodName, sig(nativeReturnType, nativeParameterTypes));

        if (pointerCount > 0) mv.label(convertResult);
        BaseMethodGenerator.convertAndReturnResult(builder, mv, resultType, nativeReturnType);

        /* --  method returns above - below is the object path -- */

        // Now implement heap object support
        if (pointerCount > 0) {
            mv.label(hasObjects);

            // Store all the native args
            LocalVariable[] tmp = new LocalVariable[parameterTypes.length];
            for (int i = parameterTypes.length - 1; i >= 0; i--) {
                tmp[i] = localVariableAllocator.allocate(long.class);
                if (float.class == nativeParameterTypes[i]) {
                    mv.invokestatic(Float.class, "floatToRawIntBits", int.class, float.class);
                    mv.i2l();

                } else if (double.class == nativeParameterTypes[i]) {
                    mv.invokestatic(Double.class, "doubleToRawLongBits", long.class, double.class);
                } else {
                    widen(mv, nativeParameterTypes[i], long.class);
                }
                mv.lstore(tmp[i]);
            }

            // Retrieve the static 'ffi' Invoker instance
            mv.getstatic(p(AbstractAsmLibraryInterface.class), "ffi", ci(com.kenai.jffi.Invoker.class));

            // retrieve the call context and function address
            mv.aload(0);
            mv.getfield(builder.getClassNamePath(), builder.getCallContextFieldName(function), ci(CallContext.class));

            mv.aload(0);
            mv.getfield(builder.getClassNamePath(), builder.getFunctionAddressFieldName(function), ci(long.class));

            // Now reload the args back onto the parameter stack
            for (int i = 0; i < parameterTypes.length; i++) {
                mv.lload(tmp[i]);
            }

            mv.iload(objCount);
            // Need to load all the converters onto the stack
            for (int i = 0; i < parameterTypes.length; i++) {
                if (pointers[i] != null) {
                    mv.aload(pointers[i]);
                    mv.aload(strategies[i]);
                    mv.aload(0);

                    ObjectParameterInfo info = ObjectParameterInfo.create(i,
                            AsmUtil.getNativeArrayFlags(parameterTypes[i].annotations));

                    mv.getfield(builder.getClassNamePath(), builder.getObjectParameterInfoName(info),
                            ci(ObjectParameterInfo.class));
                }
            }

            mv.invokevirtual(p(com.kenai.jffi.Invoker.class),
                    AbstractFastNumericMethodGenerator.getObjectParameterMethodName(parameterTypes.length),
                    AbstractFastNumericMethodGenerator.getObjectParameterMethodSignature(parameterTypes.length, pointerCount));

            // Convert the result from long/int to the correct return type
            if (float.class == nativeReturnType) {
                narrow(mv, long.class, int.class);
                mv.invokestatic(Float.class, "intBitsToFloat", float.class, int.class);

            } else if (double.class == nativeReturnType) {
                mv.invokestatic(Double.class, "longBitsToDouble", double.class, long.class);

            } else if (void.class == nativeReturnType) {
                mv.pop2();

            } else {
                convertPrimitive(mv, long.class, nativeReturnType, resultType.nativeType);
            }

            // Jump to the main conversion/boxing code above
            mv.go_to(convertResult);
        }
        mv.visitMaxs(100, calculateLocalVariableSpace(parameterTypes) + 10);
        mv.visitEnd();
    }

    void attach(Class clazz) {
        compiler.attach(clazz);
    }

    private static Class nativeParameterType(ParameterType parameterType) {
        Class javaType = parameterType.effectiveJavaType();

        if (parameterType.nativeType == NativeType.SLONG || parameterType.nativeType == NativeType.ULONG) {
            return parameterType.size()  == 4 ? int.class : long.class;

        } else {
            return AsmUtil.unboxedParameterType(javaType);
        }
    }

    private static Class nativeResultType(ResultType resultType) {
        Class javaType = resultType.effectiveJavaType();

        if (resultType.nativeType == NativeType.SLONG || resultType.nativeType == NativeType.ULONG) {
            return resultType.size()  == 4 ? int.class : long.class;

        } else {
            return AsmUtil.unboxedParameterType(javaType);
        }
    }

    private static boolean isSupportedObjectParameterType(ParameterType type) {
        return Pointer.class.isAssignableFrom(type.effectiveJavaType())
                || Struct.class.isAssignableFrom(type.effectiveJavaType())
                ;
    }


    private static boolean isSupportedType(SigType type) {
        Class javaType = type.effectiveJavaType();
        return Boolean.class.isAssignableFrom(javaType) || boolean.class == javaType
                || Byte.class.isAssignableFrom(javaType) || byte.class == javaType
                || Short.class.isAssignableFrom(javaType) || short.class == javaType
                || Integer.class.isAssignableFrom(javaType) || int.class == javaType
                || Long.class == javaType || long.class == javaType
                || Float.class == javaType || float.class == javaType
                || Double.class == javaType || double.class == javaType
                || NativeLong.class == javaType
                ;
    }


    static boolean isSupportedResult(ResultType resultType) {
        return isSupportedType(resultType) || void.class == resultType.effectiveJavaType()
                || resultType.nativeType == NativeType.ADDRESS
                ;
    }

    final static boolean isSupportedParameter(ParameterType parameterType) {
        return isSupportedType(parameterType)
                || isSupportedObjectParameterType(parameterType)
                || isDelegate(parameterType)
                ;
    }
}
