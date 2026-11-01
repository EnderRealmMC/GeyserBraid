/*
 * Copyright (c) 2026 EnderRealmMC
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package cn.enderrealm.geyserbraid.integration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Minimal reflection helper: centralizes setAccessible, field/method lookup by
 * name, and a uniform error format on failure. Nothing outside the
 * {@code integration} package may use reflection directly.
 *
 * <p>Geyser 2.12.x internals carry no API stability guarantee; when a field is
 * renamed this class surfaces it as {@link NoSuchFieldException} so callers can
 * report a clear "incompatible Geyser version" message instead of a deep NPE.</p>
 */
final class ReflectAccess {

    private ReflectAccess() {
    }

    /**
     * Reads an instance field, walking up the class hierarchy.
     *
     * @param target    the target object
     * @param fieldName the field name
     * @return the field's current value
     * @throws ReflectiveOperationException the field does not exist or cannot be accessed
     */
    static Object getField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * Writes an instance field, walking up the class hierarchy.
     *
     * @param target    the target object
     * @param fieldName the field name
     * @param value     the new value
     * @throws ReflectiveOperationException the field does not exist or cannot be accessed
     */
    static void setField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Invokes a no-argument method, walking up the class hierarchy.
     *
     * @param target     the target object
     * @param methodName the method name
     * @return the method's return value (null for void methods)
     * @throws ReflectiveOperationException the method does not exist or the call fails
     */
    static Object invokeNoArg(Object target, String methodName) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    /** Finds a field (including private ones) by walking up the hierarchy. */
    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up to the superclass
            }
        }
        throw new NoSuchFieldException(name + " (on " + type.getName() + ")");
    }

    /** Finds a no-argument method (including private ones) by walking up the hierarchy. */
    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // keep walking up to the superclass
            }
        }
        throw new NoSuchMethodException(name + " (on " + type.getName() + ")");
    }
}
