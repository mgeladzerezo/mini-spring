package io.minispring.aop.proxy;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Generates, with the JDK's {@code java.lang.classfile} API, a subclass that routes selected
 * methods through a {@link ProxyDispatcher}. For a class {@code Service} with a proxied method
 * {@code long add(int a, long b) throws IOException} the output is equivalent to:
 *
 * <pre>{@code
 * public final class Service$$MiniProxy extends Service implements GeneratedProxy {
 *     private ProxyDispatcher dispatcher;
 *
 *     public Service$$MiniProxy(Dep dep) { super(dep); }            // one per accessible constructor
 *
 *     public long add(int a, long b) throws IOException {
 *         ProxyDispatcher d = this.dispatcher;
 *         if (d == null) return super.add(a, b);                     // not activated yet
 *         return (Long) d.dispatch(this, 0, new Object[] {a, b});    // boxing and unboxing spelled out
 *     }
 *
 *     public void miniSpring$activate(ProxyDispatcher d) { this.dispatcher = d; }
 *
 *     public Object miniSpring$invokeSuper(int index, Object[] args) throws Throwable {
 *         switch (index) {
 *             case 0: return super.add((Integer) args[0], (Long) args[1]);   // invokespecial
 *             default: throw new IllegalArgumentException();
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>The class is defined as a <em>hidden class</em> in the superclass's package through a
 * private lookup. Being in the same package it can override package-private methods and call
 * package-private constructors; being hidden it needs no unique name, cannot be referenced by
 * anyone and is unloaded with its last instance, so two containers proxying the same class in
 * one JVM never collide. Stack map frames are computed by the ClassFile API.
 */
final class SubclassProxyGenerator {

    private static final String DISPATCHER_FIELD = "miniSpring$dispatcher";
    private static final ClassDesc CD_PROXY_DISPATCHER = descriptor(ProxyDispatcher.class);
    private static final ClassDesc CD_GENERATED_PROXY = descriptor(GeneratedProxy.class);
    private static final ClassDesc CD_OBJECT_ARRAY = ConstantDescs.CD_Object.arrayType();
    private static final ClassDesc CD_ILLEGAL_ARGUMENT = descriptor(IllegalArgumentException.class);
    private static final MethodTypeDesc MTD_DISPATCH = MethodTypeDesc.of(ConstantDescs.CD_Object,
            ConstantDescs.CD_Object, ConstantDescs.CD_int, CD_OBJECT_ARRAY);
    private static final MethodTypeDesc MTD_ACTIVATE = MethodTypeDesc.of(ConstantDescs.CD_void, CD_PROXY_DISPATCHER);
    private static final MethodTypeDesc MTD_INVOKE_SUPER = MethodTypeDesc.of(ConstantDescs.CD_Object,
            ConstantDescs.CD_int, CD_OBJECT_ARRAY);

    private SubclassProxyGenerator() {
    }

    /**
     * Generates and defines the proxy class.
     *
     * @param superclass the class to extend; already validated as extensible
     * @param methods    the methods to override; the position in this list is the method index
     */
    static Class<?> define(Class<?> superclass, List<Method> methods) {
        byte[] bytecode = generate(superclass, methods);
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(superclass, MethodHandles.lookup());
            return lookup.defineHiddenClass(bytecode, false).lookupClass();
        } catch (IllegalAccessException | LinkageError e) {
            throw new ProxyCreationException("Cannot define a proxy subclass of " + superclass.getName() + ": " + e, e);
        }
    }

    static byte[] generate(Class<?> superclass, List<Method> methods) {
        ClassDesc superDesc = descriptor(superclass);
        ClassDesc thisDesc = ClassDesc.ofDescriptor("L" + superclass.getName().replace('.', '/') + "$$MiniProxy;");
        // Frame computation asks how classes relate. The proxy class does not exist yet, so its place in
        // the hierarchy is stated up front; everything else is answered by loading from the user's loader.
        ClassHierarchyResolver hierarchy = ClassHierarchyResolver.of(List.of(), Map.of(thisDesc, superDesc))
                .orElse(ClassHierarchyResolver.ofClassLoading(superclass.getClassLoader()))
                .orElse(ClassHierarchyResolver.defaultResolver());

        return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchy)).build(thisDesc, classBuilder -> {
            classBuilder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_SYNTHETIC);
            classBuilder.withSuperclass(superDesc);
            classBuilder.withInterfaceSymbols(CD_GENERATED_PROXY);
            classBuilder.withField(DISPATCHER_FIELD, CD_PROXY_DISPATCHER, ClassFile.ACC_PRIVATE);

            for (Constructor<?> constructor : superclass.getDeclaredConstructors()) {
                if (!Modifier.isPrivate(constructor.getModifiers())) {
                    constructor(classBuilder, superDesc, constructor);
                }
            }
            for (int index = 0; index < methods.size(); index++) {
                override(classBuilder, thisDesc, superDesc, methods.get(index), index);
            }
            classBuilder.withMethodBody("miniSpring$activate", MTD_ACTIVATE, ClassFile.ACC_PUBLIC, code -> code
                    .aload(0)
                    .aload(1)
                    .putfield(thisDesc, DISPATCHER_FIELD, CD_PROXY_DISPATCHER)
                    .return_());
            classBuilder.withMethodBody("miniSpring$invokeSuper", MTD_INVOKE_SUPER, ClassFile.ACC_PUBLIC,
                    code -> invokeSuperSwitch(code, superDesc, methods));
        });
    }

    /** {@code Proxy(args...) { super(args...); }} */
    private static void constructor(ClassBuilder classBuilder, ClassDesc superDesc, Constructor<?> constructor) {
        MethodTypeDesc type = MethodTypeDesc.of(ConstantDescs.CD_void, descriptors(constructor.getParameterTypes()));
        classBuilder.withMethod(ConstantDescs.INIT_NAME, type, ClassFile.ACC_PUBLIC, methodBuilder -> {
            declareExceptions(methodBuilder, constructor.getExceptionTypes());
            methodBuilder.withCode(code -> {
                code.aload(0);
                loadParameters(code, constructor.getParameterTypes());
                code.invokespecial(superDesc, ConstantDescs.INIT_NAME, type);
                code.return_();
            });
        });
    }

    private static void override(ClassBuilder classBuilder, ClassDesc thisDesc, ClassDesc superDesc, Method method,
                                 int index) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        Class<?> returnType = method.getReturnType();
        MethodTypeDesc type = MethodTypeDesc.of(descriptor(returnType), descriptors(parameterTypes));
        int flags = method.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED);
        if (method.isVarArgs()) {
            flags |= ClassFile.ACC_VARARGS;
        }
        classBuilder.withMethod(method.getName(), type, flags, methodBuilder -> {
            declareExceptions(methodBuilder, method.getExceptionTypes());
            methodBuilder.withCode(code -> {
                Label callSuperDirectly = code.newLabel();
                int dispatcher = code.allocateLocal(TypeKind.REFERENCE);
                code.aload(0).getfield(thisDesc, DISPATCHER_FIELD, CD_PROXY_DISPATCHER).astore(dispatcher);
                code.aload(dispatcher).ifnull(callSuperDirectly);

                // dispatcher.dispatch(this, index, new Object[] { boxed arguments })
                code.aload(dispatcher).aload(0).loadConstant(index);
                code.loadConstant(parameterTypes.length).anewarray(ConstantDescs.CD_Object);
                for (int i = 0; i < parameterTypes.length; i++) {
                    code.dup().loadConstant(i);
                    code.loadLocal(TypeKind.from(parameterTypes[i]), code.parameterSlot(i));
                    box(code, parameterTypes[i]);
                    code.aastore();
                }
                code.invokeinterface(CD_PROXY_DISPATCHER, "dispatch", MTD_DISPATCH);
                if (returnType == void.class) {
                    code.pop().return_();
                } else {
                    unbox(code, returnType);
                    code.return_(TypeKind.from(returnType));
                }

                // Reached while the dispatcher is unset: during the superclass constructor and until the
                // container activates the proxy, the object must behave like a plain instance.
                code.labelBinding(callSuperDirectly);
                code.aload(0);
                loadParameters(code, parameterTypes);
                code.invokespecial(superDesc, method.getName(), type);
                code.return_(TypeKind.from(returnType));
            });
        });
    }

    /** One {@code tableswitch} arm per proxied method, each unpacking the array and calling {@code super}. */
    private static void invokeSuperSwitch(CodeBuilder code, ClassDesc superDesc, List<Method> methods) {
        Label unknownIndex = code.newLabel();
        if (!methods.isEmpty()) {
            List<Label> arms = new ArrayList<>();
            List<SwitchCase> cases = new ArrayList<>();
            for (int index = 0; index < methods.size(); index++) {
                Label arm = code.newLabel();
                arms.add(arm);
                cases.add(SwitchCase.of(index, arm));
            }
            code.iload(1).tableswitch(0, methods.size() - 1, unknownIndex, cases);
            for (int index = 0; index < methods.size(); index++) {
                Method method = methods.get(index);
                Class<?>[] parameterTypes = method.getParameterTypes();
                code.labelBinding(arms.get(index));
                code.aload(0);
                for (int i = 0; i < parameterTypes.length; i++) {
                    code.aload(2).loadConstant(i).aaload();
                    unbox(code, parameterTypes[i]);
                }
                code.invokespecial(superDesc, method.getName(),
                        MethodTypeDesc.of(descriptor(method.getReturnType()), descriptors(parameterTypes)));
                if (method.getReturnType() == void.class) {
                    code.aconst_null();
                } else {
                    box(code, method.getReturnType());
                }
                code.areturn();
            }
        }
        code.labelBinding(unknownIndex);
        code.new_(CD_ILLEGAL_ARGUMENT).dup()
                .invokespecial(CD_ILLEGAL_ARGUMENT, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                .athrow();
    }

    /** Loads every parameter; {@code long} and {@code double} occupy two slots, which parameterSlot accounts for. */
    private static void loadParameters(CodeBuilder code, Class<?>[] parameterTypes) {
        for (int i = 0; i < parameterTypes.length; i++) {
            code.loadLocal(TypeKind.from(parameterTypes[i]), code.parameterSlot(i));
        }
    }

    /** Keeps the {@code throws} clause visible to reflection; the verifier itself does not care. */
    private static void declareExceptions(MethodBuilder methodBuilder, Class<?>[] exceptionTypes) {
        if (exceptionTypes.length > 0) {
            methodBuilder.with(ExceptionsAttribute.ofSymbols(descriptors(exceptionTypes)));
        }
    }

    /** {@code int -> Integer.valueOf(int)} and so on; references are left alone. */
    private static void box(CodeBuilder code, Class<?> type) {
        if (type.isPrimitive()) {
            ClassDesc wrapper = descriptor(wrapperOf(type));
            code.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, descriptor(type)));
        }
    }

    /** {@code (Integer) value).intValue()} for primitives, a checked cast for other reference types. */
    private static void unbox(CodeBuilder code, Class<?> type) {
        if (type.isPrimitive()) {
            ClassDesc wrapper = descriptor(wrapperOf(type));
            code.checkcast(wrapper);
            code.invokevirtual(wrapper, type.getName() + "Value", MethodTypeDesc.of(descriptor(type)));
        } else if (type != Object.class) {
            code.checkcast(descriptor(type));
        }
    }

    private static Class<?> wrapperOf(Class<?> primitive) {
        return MethodType.methodType(primitive).wrap().returnType();
    }

    private static ClassDesc descriptor(Class<?> type) {
        return ClassDesc.ofDescriptor(type.descriptorString());
    }

    private static ClassDesc[] descriptors(Class<?>[] types) {
        return Arrays.stream(types).map(SubclassProxyGenerator::descriptor).toArray(ClassDesc[]::new);
    }
}
