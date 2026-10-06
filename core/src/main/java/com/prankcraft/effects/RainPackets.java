package com.prankcraft.effects;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Sends the vanilla weather game-event packet to a single player.
 *
 * <p>Everything here is reflective on purpose. The class name, the nested {@code Type} enum and
 * the packet constructor have all changed shape between 1.16 and current Paper, and this jar has
 * to load on all of them. Reflection is resolved once, cached, and any failure is reported as
 * "unsupported" rather than thrown into a scheduler tick.
 *
 * <p>Packet path, by version:
 * <ul>
 *   <li>1.20.2+ : {@code ClientboundGameEventPacket(Type, int)}</li>
 *   <li>1.16-1.20.1 : {@code ClientboundGameEventPacket(Type, float)}</li>
 * </ul>
 * Only the constructor's second parameter type differs, which is why both are probed.
 */
final class RainPackets {

    private static boolean initialised;
    private static boolean supported;

    private static Object startRain;
    private static Object stopRain;
    private static Object startThunder;
    private static Object stopThunder;

    private static Constructor<?> packetConstructor;
    private static Field connectionField;
    private static Method sendMethod;

    private RainPackets() {
    }

    static boolean start(Player player, boolean thunder) {
        if (!supported(player)) {
            return false;
        }
        return thunder ? send(player, startThunder, 1) : send(player, startRain, 1);
    }

    static boolean stop(Player player) {
        if (!supported(player)) {
            return false;
        }
        boolean ok = send(player, stopRain, 0);
        send(player, stopThunder, 0);
        return ok;
    }

    private static boolean supported(Player player) {
        if (!initialised) {
            initialise(player);
            initialised = true;
        }
        return supported;
    }

    private static void initialise(Player player) {
        try {
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundGameEventPacket");
            Class<?> typeClass = Class.forName("net.minecraft.network.protocol.game.ClientboundGameEventPacket$Type");

            startRain = enumConstant(typeClass, "START_RAINING");
            stopRain = enumConstant(typeClass, "STOP_RAINING");
            startThunder = enumConstant(typeClass, "START_THUNDERING");
            stopThunder = enumConstant(typeClass, "STOP_THUNDERING");
            if (startRain == null || stopRain == null || startThunder == null || stopThunder == null) {
                return;
            }

            packetConstructor = constructor(packetClass, typeClass, int.class);
            if (packetConstructor == null) {
                packetConstructor = constructor(packetClass, typeClass, float.class);
            }
            if (packetConstructor == null) {
                return;
            }

            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            connectionField = findConnectionField(handle.getClass());
            if (connectionField == null) {
                return;
            }
            connectionField.setAccessible(true);
            Object connection = connectionField.get(handle);
            if (connection == null) {
                return;
            }
            sendMethod = findSendMethod(connection.getClass());
            supported = sendMethod != null;
        } catch (Throwable ignored) {
            supported = false;
        }
    }

    private static Constructor<?> constructor(Class<?> owner, Class<?> first, Class<?> second) {
        try {
            return owner.getConstructor(first, second);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object enumConstant(Class<?> enumClass, String name) {        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Walks up the class hierarchy looking for the connection field, whatever it is called now. */
    private static Field findConnectionField(Class<?> from) {
        for (Class<?> type = from; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                String simple = field.getType().getName();
                if (simple.startsWith("net.minecraft.server.network.")
                        || simple.endsWith("ServerGamePacketListenerImpl")
                        || simple.endsWith("PlayerConnection")) {
                    return field;
                }
            }
        }
        return null;
    }

    /** The only public void method on the connection that accepts a packet is send(). */
    private static Method findSendMethod(Class<?> from) {
        for (Class<?> type = from; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1
                        && method.getReturnType() == void.class
                        && params[0].getName().endsWith("Packet")) {
                    return method;
                }
            }
        }
        return null;
    }

    private static boolean send(Player player, Object eventType, int data) {
        if (!supported || eventType == null || packetConstructor == null) {
            return false;
        }
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object connection = connectionField.get(handle);
            if (connection == null) {
                return false;
            }
            Class<?> expected = packetConstructor.getParameterTypes()[1];
            Object value = expected == float.class ? (Object) (float) data : (Object) data;
            Object packet = packetConstructor.newInstance(eventType, value);
            sendMethod.invoke(connection, packet);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
