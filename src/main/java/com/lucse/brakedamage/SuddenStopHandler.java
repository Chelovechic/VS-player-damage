package com.lucse.brakedamage;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedList;
import java.util.List;

public class SuddenStopHandler {

    private static final boolean DEBUG_ENABLED = false; // Включить/выключить логи




    private static final double SIMPLE_MIN_SPEED_FOR_TRACKING_MPS = 3.0D;
    private static final double SIMPLE_DECELERATION_G_THRESHOLD = 4.0D;
    private static final double SIMPLE_VEHICLE_DECELERATION_G_THRESHOLD = 8.0D;
    private static final double SIMPLE_VERTICAL_DECELERATION_G_THRESHOLD = 2.5D;

    private static final int SIMPLE_G_FORCE_CHECK_TICKS = 8;
    private static final double SIMPLE_MIN_DIRECTION_CHANGE = 0.3D;
    private static final double SIMPLE_MIN_SPEED_CHANGE_MPS = 3.5D;
    private static final double SIMPLE_MIN_SPEED_CHANGE_PERCENT = 0.6D;

    private static final double SIMPLE_SPEED_STABILITY_THRESHOLD = 0.25D;

    private static final int SIMPLE_FREEZE_CHECK_TICKS = 12;
    private static final double SIMPLE_FREEZE_SPEED_THRESHOLD = 1.5D;
    private static final int SIMPLE_MIN_TICKS_AT_LOW_SPEED = 6;

    private static final double SIMPLE_MAX_REASONABLE_DISTANCE_PER_TICK = 20.0D;

    private static final double SIMPLE_MAX_VERTICAL_FALL_RATIO = 0.8D;
    private static final double SIMPLE_MAX_HORIZONTAL_SPEED_FOR_FALL = 5.0D;
    private static final double SIMPLE_VERTICAL_MIN_SPEED_CHANGE_MPS = 4.0D;


    private static final double MIN_SPEED_FOR_TRACKING_MPS = 5.0D;
    private static final double GRAVITY_ACCELERATION = 9.81D;

    private static final double DECELERATION_G_THRESHOLD = 6.0D;
    private static final double VERTICAL_DECELERATION_G_THRESHOLD = 4.0D;

    private static final double MIN_SPEED_CHANGE_MPS = 5.0D;
    private static final double MIN_SPEED_CHANGE_PERCENT = 0.7D;

    private static final double IMPACT_DIRECTION_CHANGE = 0.5D;
    private static final double MIN_IMPACT_SPEED = 10.0D;


    private static final int MOVEMENT_ANALYSIS_TICKS = 15;
    private static final double MAX_REASONABLE_SPEED_MPS = 150.0D;


    private static final double SPEED_STEP_MPS = 5.0D;
    private static final float BASE_DAMAGE = 1.0F;

    private static final float[] SPEED_DAMAGE_MULTIPLIER = {
            1.0f,
            1.5f,
            2.0f,
            3.0f,
            5.0f,
            8.0f
    };

    private static final float[] GFORCE_DAMAGE_MULTIPLIER = {
            0.5f,
            1.0f,
            1.5f,
            2.0f,
            3.0f
    };

    private static final int DAMAGE_COOLDOWN_TICKS = 40;


    private static final int LOG_COOLDOWN_TICKS = 20;
    private static final double LOG_SPEED_THRESHOLD = 2.0D;
    private static final int MAX_LOG_LINES = 10;
    private static final int LOG_LIFETIME_TICKS = 100;


    private static class PlayerSpeedData {
        Deque<Vec3> velocityHistory = new ArrayDeque<>();
        Deque<Double> speedMagnitudeHistory = new ArrayDeque<>();
        Vec3 previousPlayerPosition = null;
        boolean wasInCreative = false;
        int ticksSinceModeChange = 0;
        int ticksSinceLastDamage = 0;
        int ticksSinceLastLog = 0;
        int logSequenceNumber = 0;


        double previousHighSpeed = 0.0D;
        int ticksAtLowSpeed = 0;


        List<LogEntry> logEntries = new LinkedList<>();
    }

    private static class LogEntry {
        String message;
        int age;
        boolean isImportant;

        LogEntry(String message, boolean isImportant) {
            this.message = message;
            this.age = 0;
            this.isImportant = isImportant;
        }
    }

    private final Map<UUID, PlayerSpeedData> playerSpeedData = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Player player = event.player;
        if (player.level().isClientSide) return;

        UUID playerId = player.getUUID();
        PlayerSpeedData data = playerSpeedData.computeIfAbsent(playerId, k -> new PlayerSpeedData());

        updateAndCleanLogs(data);

        boolean isInCreative = player.getAbilities().instabuild;
        if (data.wasInCreative != isInCreative) {
            data.velocityHistory.clear();
            data.speedMagnitudeHistory.clear();
            data.previousPlayerPosition = null;
            data.ticksSinceModeChange = 0;
            data.ticksSinceLastDamage = 0;
            data.ticksSinceLastLog = 0;
            data.logSequenceNumber = 0;
            data.previousHighSpeed = 0.0D;
            data.ticksAtLowSpeed = 0;
            data.logEntries.clear();
            data.wasInCreative = isInCreative;
        }
        data.ticksSinceModeChange++;
        data.ticksSinceLastDamage++;

        if (data.ticksSinceModeChange < 10) return;
        if (!shouldApplyDamage(player)) return;

        // ======== РАЗДЕЛЕНИЕ ЛОГИКИ: СТОЯЩИЕ ИГРОКИ VS ПАССАЖИРЫ (ебучий valkyrien skies лагает) ========
        if (player.isPassenger()) {
            processPassengerLogic(player, data);
        } else {
            processStandingPlayerLogic(player, data);
        }
    }


    private void processPassengerLogic(Player player, PlayerSpeedData data) {
        Vec3 currentPlayerPosition = player.position();

        // Исключаем нахуй элитры
        if (player.isFallFlying()) {
            ItemStack chestItem = player.getItemBySlot(EquipmentSlot.CHEST);
            if (chestItem.getItem() == Items.ELYTRA) {
                data.previousPlayerPosition = currentPlayerPosition;
                return;
            }
        }


        Vec3 currentVelocity = Vec3.ZERO;
        double speed = 0.0D;
        double horizontalSpeed = 0.0D;
        double verticalSpeed = 0.0D;

        if (data.previousPlayerPosition != null) {
            Vec3 delta = currentPlayerPosition.subtract(data.previousPlayerPosition);
            currentVelocity = delta.scale(20.0D);
            speed = currentVelocity.length();
            horizontalSpeed = Math.sqrt(currentVelocity.x * currentVelocity.x + currentVelocity.z * currentVelocity.z);
            verticalSpeed = Math.abs(currentVelocity.y);

            if (speed > MAX_REASONABLE_SPEED_MPS) {
                data.previousPlayerPosition = currentPlayerPosition;
                return;
            }
        }

        // Апдейт хистори
        data.velocityHistory.addLast(currentVelocity);
        while (data.velocityHistory.size() > MOVEMENT_ANALYSIS_TICKS) {
            data.velocityHistory.removeFirst();
        }

        data.speedMagnitudeHistory.addLast(speed);
        while (data.speedMagnitudeHistory.size() > MOVEMENT_ANALYSIS_TICKS) {
            data.speedMagnitudeHistory.removeFirst();
        }


        if (DEBUG_ENABLED && data.ticksSinceLastLog >= LOG_COOLDOWN_TICKS && speed >= LOG_SPEED_THRESHOLD) {
            data.logSequenceNumber++;
            String playerState = getPlayerState(player);

            String logMessage = String.format("§7[%03d] §fSpeed:§e%.1f §fH:§a%.1f §fV:§c%.1f §fPassenger:§2YES",
                    data.logSequenceNumber, speed, horizontalSpeed, verticalSpeed);

            addLogEntry(data, logMessage, false);

            System.out.printf("[BrakeDamage] %s | Speed:%.1f m/s | OnGround:%s%n",
                    player.getName().getString(), speed, player.onGround());

            data.ticksSinceLastLog = 0;
        }

        if (data.velocityHistory.size() < 5) {
            data.previousPlayerPosition = currentPlayerPosition;
            return;
        }


        boolean isCollisionImpact = detectCollisionImpact(data.velocityHistory);
        boolean isFallImpact = detectFallImpact(data.velocityHistory, player);


        if (DEBUG_ENABLED && (isCollisionImpact || isFallImpact)) {
            String checkResults = String.format("§6Checks: §fCollision=§e%s §fFall=§e%s §fGround=§a%s",
                    isCollisionImpact, isFallImpact, player.onGround());

            addLogEntry(data, checkResults, true);
        }

        boolean shouldDamage = false;
        double gForce = 0.0D;
        double maxSpeed = 0.0D;
        String damageType = "";

        if (isCollisionImpact) {
            maxSpeed = 0.0D;
            for (Vec3 vel : data.velocityHistory) {
                maxSpeed = Math.max(maxSpeed, vel.length());
            }

            if (maxSpeed >= MIN_IMPACT_SPEED) {
                gForce = calculateCollisionGForce(data.velocityHistory);

                if (gForce >= DECELERATION_G_THRESHOLD) {
                    shouldDamage = true;
                    damageType = "COLLISION_PASSENGER";

                    if (DEBUG_ENABLED) {
                        String collisionMsg = String.format("§c⚠ ДТП §f| Скорость:§e%.1f м/с §fG:§c%.1f",
                                maxSpeed, gForce);
                        addLogEntry(data, collisionMsg, true);
                    }
                }
            }
        } else if (isFallImpact && player.onGround()) {
            double currentVerticalSpeed = Math.abs(data.velocityHistory.getLast().y);
            if (currentVerticalSpeed > 4.0D) {
                shouldDamage = true;
                damageType = "FALL_PASSENGER";
                maxSpeed = currentVerticalSpeed;

                if (DEBUG_ENABLED) {
                    addLogEntry(data, String.format("§9⚠ Падение §f| Скорость:§c%.1f м/с", currentVerticalSpeed), true);
                }
            }
        }

        // Нанесение урона
        if (shouldDamage && data.ticksSinceLastDamage >= DAMAGE_COOLDOWN_TICKS) {
            if (maxSpeed == 0.0D) {
                for (Vec3 vel : data.velocityHistory) maxSpeed = Math.max(maxSpeed, vel.length());
            }


            float damage = calculateRealisticDamage(maxSpeed, gForce, true);

            if (DEBUG_ENABLED) {
                String damageLog = String.format("§4⚡ %s §fУрон:§c%.1f HP §f| Скорость:§e%.1f м/с §fG:§6%.1f",
                        getDamageSeverity(damage), damage, maxSpeed, gForce);

                addLogEntry(data, damageLog, true);

                String details = String.format("§7   На земле:%s Пассажир:%s",
                        player.onGround(), player.isPassenger());
                addLogEntry(data, details, false);

                logDamageDetails(player, damage, maxSpeed, gForce, damageType, player.position(), data);
            }

            if (damage > 0.0F) {
                player.hurt(player.damageSources().generic(), damage);

                data.velocityHistory.clear();
                data.speedMagnitudeHistory.clear();
                data.ticksSinceLastDamage = 0;
                data.ticksSinceLastLog = LOG_COOLDOWN_TICKS;
            }
        }


        if (DEBUG_ENABLED && !data.logEntries.isEmpty()) {
            displayLogsOnScreen(player, data);
        }

        data.previousPlayerPosition = player.position();
    }


    private void processStandingPlayerLogic(Player player, PlayerSpeedData data) {
        Vec3 currentPlayerPosition = player.position();


        if (player.isFallFlying()) {
            ItemStack chestItem = player.getItemBySlot(EquipmentSlot.CHEST);
            if (chestItem.getItem() == Items.ELYTRA) {
                data.previousPlayerPosition = currentPlayerPosition;
                return;
            }
        }


        Vec3 currentVelocity = Vec3.ZERO;

        if (data.previousPlayerPosition != null) {
            Vec3 delta = currentPlayerPosition.subtract(data.previousPlayerPosition);
            double distance = delta.length();


            if (distance > SIMPLE_MAX_REASONABLE_DISTANCE_PER_TICK) {
                data.velocityHistory.clear();
                data.speedMagnitudeHistory.clear();
                data.previousPlayerPosition = currentPlayerPosition;
                data.previousHighSpeed = 0.0D;
                data.ticksAtLowSpeed = 0;
                return;
            }

            currentVelocity = delta.scale(20.0D);

            double speed = currentVelocity.length();
            if (speed > MAX_REASONABLE_SPEED_MPS) {
                data.previousPlayerPosition = currentPlayerPosition;
                return;
            }
        }

        double currentSpeed = currentVelocity.length();


        data.velocityHistory.addLast(currentVelocity);
        while (data.velocityHistory.size() > SIMPLE_G_FORCE_CHECK_TICKS + 1) {
            data.velocityHistory.removeFirst();
        }

        data.speedMagnitudeHistory.addLast(currentSpeed);
        while (data.speedMagnitudeHistory.size() > SIMPLE_FREEZE_CHECK_TICKS) {
            data.speedMagnitudeHistory.removeFirst();
        }


        if (currentSpeed >= SIMPLE_MIN_SPEED_FOR_TRACKING_MPS) {
            data.previousHighSpeed = Math.max(data.previousHighSpeed, currentSpeed);
            data.ticksAtLowSpeed = 0;
        } else {
            data.ticksAtLowSpeed++;
        }


        if (data.velocityHistory.size() < 2 || data.speedMagnitudeHistory.size() < SIMPLE_MIN_TICKS_AT_LOW_SPEED + 1) {
            data.previousPlayerPosition = currentPlayerPosition;
            return;
        }


        boolean isMicroFreeze = isMicroFreeze(data.speedMagnitudeHistory, data.previousHighSpeed);

        if (isMicroFreeze) {
            data.previousPlayerPosition = currentPlayerPosition;
            return;
        }


        double gForce = calculateGForceSimple(data.velocityHistory);


        if (currentSpeed < SIMPLE_MIN_SPEED_FOR_TRACKING_MPS && gForce < SIMPLE_DECELERATION_G_THRESHOLD) {
            data.previousPlayerPosition = currentPlayerPosition;
            return;
        }


        Vec3 oldestVelocity = data.velocityHistory.getFirst();
        Vec3 newestVelocity = data.velocityHistory.getLast();
        double oldestSpeed = oldestVelocity.length();
        double newestSpeed = newestVelocity.length();
        double speedChange = newestSpeed - oldestSpeed;
        double speedChangeAbs = Math.abs(speedChange);


        double verticalVelocity = Math.abs(newestVelocity.y);
        double horizontalVelocity = Math.sqrt(newestVelocity.x * newestVelocity.x + newestVelocity.z * newestVelocity.z);
        double totalSpeedForFall = newestVelocity.length();
        double verticalRatio = totalSpeedForFall > 0.1D ? verticalVelocity / totalSpeedForFall : 0.0D;
        boolean isFalling = verticalRatio > SIMPLE_MAX_VERTICAL_FALL_RATIO && newestVelocity.y < 0.0D && horizontalVelocity < SIMPLE_MAX_HORIZONTAL_SPEED_FOR_FALL;


        boolean isVerticalMovement = verticalRatio > 0.5D;


        double avgSpeed = 0.0D;
        for (Vec3 vel : data.velocityHistory) {
            avgSpeed += vel.length();
        }
        avgSpeed /= data.velocityHistory.size();

        double speedVariation = 0.0D;
        for (Vec3 vel : data.velocityHistory) {
            double diff = Math.abs(vel.length() - avgSpeed);
            speedVariation = Math.max(speedVariation, diff);
        }
        double speedVariationPercent = avgSpeed > 0.01D ? speedVariation / avgSpeed : 0.0D;
        boolean isSpeedStable = speedVariationPercent < SIMPLE_SPEED_STABILITY_THRESHOLD;


        double speedChangePercent = oldestSpeed > 0.01D ? speedChangeAbs / oldestSpeed : 0.0D;
        boolean hasSignificantSpeedChange = speedChangeAbs >= SIMPLE_MIN_SPEED_CHANGE_MPS &&
                speedChangePercent >= SIMPLE_MIN_SPEED_CHANGE_PERCENT;


        boolean isDeceleration = speedChange < -SIMPLE_MIN_SPEED_CHANGE_MPS && speedChangePercent >= SIMPLE_MIN_SPEED_CHANGE_PERCENT;


        boolean hasDirectionChange = false;
        if (oldestSpeed > 0.1D && newestSpeed > 0.1D) {
            Vec3 oldDirection = oldestVelocity.normalize();
            Vec3 newDirection = newestVelocity.normalize();
            Vec3 directionChange = newDirection.subtract(oldDirection);
            double directionChangeMagnitude = directionChange.length();
            hasDirectionChange = directionChangeMagnitude >= SIMPLE_MIN_DIRECTION_CHANGE;
        }


        boolean isRealDeceleration = (data.ticksAtLowSpeed >= SIMPLE_MIN_TICKS_AT_LOW_SPEED) &&
                (oldestSpeed - currentSpeed) > SIMPLE_MIN_SPEED_CHANGE_MPS * 0.5;

        boolean isOnGround = player.onGround();


        if (isFalling && !isOnGround) {
            data.previousPlayerPosition = currentPlayerPosition;
            return;
        }


        boolean useVerticalThresholds = isVerticalMovement && newestVelocity.y < 0.0D && speedChangeAbs >= 5.0D;
        double speedChangeThreshold = useVerticalThresholds ? SIMPLE_VERTICAL_MIN_SPEED_CHANGE_MPS : SIMPLE_MIN_SPEED_CHANGE_MPS;
        double speedChangePercentThreshold = useVerticalThresholds ? 0.9D : SIMPLE_MIN_SPEED_CHANGE_PERCENT;
        double gThresholdForCheck = useVerticalThresholds ? SIMPLE_VERTICAL_DECELERATION_G_THRESHOLD : SIMPLE_DECELERATION_G_THRESHOLD;


        boolean hasSignificantSpeedChangeForCheck = speedChangeAbs >= speedChangeThreshold &&
                speedChangePercent >= speedChangePercentThreshold;
        boolean isDecelerationForCheck = speedChange < -speedChangeThreshold && speedChangePercent >= speedChangePercentThreshold;

        boolean shouldDamage = false;
        double maxSpeed = 0.0D;
        String damageType = "";


        if (!isFalling && !isSpeedStable && hasSignificantSpeedChangeForCheck && isDecelerationForCheck && isRealDeceleration) {
            if (gForce >= gThresholdForCheck) {
                if (hasDirectionChange || gForce >= gThresholdForCheck * 1.5D) {
                    shouldDamage = true;
                    damageType = "COLLISION_STANDING";
                }
            }
        }

        else if (isFalling && isOnGround && hasSignificantSpeedChangeForCheck && isDecelerationForCheck) {
            if (oldestSpeed >= SIMPLE_MIN_SPEED_FOR_TRACKING_MPS && gForce >= gThresholdForCheck) {
                shouldDamage = true;
                damageType = "FALL_STANDING";
            }
        }


        if (shouldDamage && data.ticksSinceLastDamage >= DAMAGE_COOLDOWN_TICKS) {

            for (Vec3 vel : data.velocityHistory) {
                maxSpeed = Math.max(maxSpeed, vel.length());
            }


            float damage = calculateRealisticDamage(maxSpeed, gForce, false);

            if (DEBUG_ENABLED) {
                String damageLog = String.format("§4⚡ %s §fУрон:§c%.1f HP §f| Скорость:§e%.1f м/с §fG:§6%.1f",
                        getDamageSeverity(damage), damage, maxSpeed, gForce);
                addLogEntry(data, damageLog, true);

                System.out.printf("[BrakeDamage] %s - %s: Скорость=%.1f м/с G=%.1f Урон=%.1f%n",
                        player.getName().getString(), damageType, maxSpeed, gForce, damage);
            }

            if (damage > 0.0F) {
                player.hurt(player.damageSources().generic(), damage);


                while (data.velocityHistory.size() > 4) {
                    data.velocityHistory.removeFirst();
                }
                while (data.speedMagnitudeHistory.size() > 4) {
                    data.speedMagnitudeHistory.removeFirst();
                }
                data.ticksSinceLastDamage = 0;
                data.previousHighSpeed = Math.max(data.previousHighSpeed * 0.5D, currentSpeed);
                data.ticksSinceLastLog = LOG_COOLDOWN_TICKS;
            }
        }


        if (DEBUG_ENABLED && !data.logEntries.isEmpty()) {
            displayLogsOnScreen(player, data);
        }

        data.previousPlayerPosition = currentPlayerPosition;
    }


    private boolean isMicroFreeze(Deque<Double> speedHistory, double previousHighSpeed) {
        if (speedHistory.size() < SIMPLE_FREEZE_CHECK_TICKS || previousHighSpeed < SIMPLE_MIN_SPEED_FOR_TRACKING_MPS) {
            return false;
        }

        Double[] speeds = speedHistory.toArray(new Double[0]);
        double currentSpeed = speeds[speeds.length - 1];

        if (currentSpeed >= SIMPLE_MIN_SPEED_FOR_TRACKING_MPS) {
            int lowSpeedCount = 0;
            boolean hasHighSpeedBefore = false;
            boolean hasHighSpeedAfter = false;

            for (int i = 0; i < speeds.length; i++) {
                double speed = speeds[i];
                if (speed < SIMPLE_FREEZE_SPEED_THRESHOLD) {
                    lowSpeedCount++;
                } else if (speed >= SIMPLE_MIN_SPEED_FOR_TRACKING_MPS) {
                    if (i < speeds.length / 3) {
                        hasHighSpeedBefore = true;
                    }
                    if (i > speeds.length * 2 / 3) {
                        hasHighSpeedAfter = true;
                    }
                }
            }

            return hasHighSpeedBefore && hasHighSpeedAfter &&
                    lowSpeedCount > 0 && lowSpeedCount < speeds.length * 0.8;
        }

        return false;
    }



    private double calculateGForceSimple(Deque<Vec3> velocityHistory) {
        if (velocityHistory.size() < 2) {
            return 0.0D;
        }

        Vec3 initialVelocity = velocityHistory.getFirst();
        Vec3 finalVelocity = velocityHistory.getLast();

        double initialSpeed = initialVelocity.length();
        double finalSpeed = finalVelocity.length();
        double speedChange = finalSpeed - initialSpeed;

        if (speedChange >= 0.0D) {
            return 0.0D;
        }

        double timeDelta = (velocityHistory.size() - 1) / 20.0D;

        if (timeDelta < 0.001D) {
            return 0.0D;
        }

        double speedChangeMagnitude = Math.abs(speedChange);
        double decelerationMagnitude = speedChangeMagnitude / timeDelta;

        return decelerationMagnitude / GRAVITY_ACCELERATION;
    }


    private float calculateRealisticDamage(double maxSpeed, double gForce, boolean isPassenger) {
        if (maxSpeed < MIN_SPEED_FOR_TRACKING_MPS) return 0.0F;

        // Коэффициент защиты (пассажиры защищены лучше)
        float protectionFactor = isPassenger ? 0.7f : 1.0f;

        // Определяем множитель по скорости
        float speedMultiplier = 1.0f;
        if (maxSpeed >= 25.0D) speedMultiplier = SPEED_DAMAGE_MULTIPLIER[5];
        else if (maxSpeed >= 20.0D) speedMultiplier = SPEED_DAMAGE_MULTIPLIER[4];
        else if (maxSpeed >= 15.0D) speedMultiplier = SPEED_DAMAGE_MULTIPLIER[3];
        else if (maxSpeed >= 10.0D) speedMultiplier = SPEED_DAMAGE_MULTIPLIER[2];
        else if (maxSpeed >= 5.0D) speedMultiplier = SPEED_DAMAGE_MULTIPLIER[1];

        // Определяем множитель по перегрузке
        float gForceMultiplier = 1.0f;
        if (gForce >= 20.0D) gForceMultiplier = GFORCE_DAMAGE_MULTIPLIER[4];
        else if (gForce >= 15.0D) gForceMultiplier = GFORCE_DAMAGE_MULTIPLIER[3];
        else if (gForce >= 10.0D) gForceMultiplier = GFORCE_DAMAGE_MULTIPLIER[2];
        else if (gForce >= 5.0D) gForceMultiplier = GFORCE_DAMAGE_MULTIPLIER[1];


        float baseDamageFromSpeed = (float)(maxSpeed / 10.0D) * BASE_DAMAGE;


        float damage = baseDamageFromSpeed * speedMultiplier * gForceMultiplier * protectionFactor;


        return Math.min(damage, 20.0f);
    }


    private String getDamageSeverity(float damage) {

    }


    private void displayLogsOnScreen(Player player, PlayerSpeedData data) {
        int linesToShow = Math.min(MAX_LOG_LINES, data.logEntries.size());

        StringBuilder allLogs = new StringBuilder();


        int startIndex = Math.max(0, data.logEntries.size() - linesToShow);
        int linesAdded = 0;

        for (int i = startIndex; i < data.logEntries.size() && linesAdded < MAX_LOG_LINES; i++) {
            LogEntry entry = data.logEntries.get(i);

            int maxAge = entry.isImportant ? LOG_LIFETIME_TICKS * 2 : LOG_LIFETIME_TICKS;
            double ageRatio = (double) entry.age / maxAge;

            if (ageRatio < 0.8) {
                allLogs.append(entry.message).append("\n");
                linesAdded++;
            }
        }



        if (linesAdded > 0) {
            player.displayClientMessage(Component.literal(allLogs.toString()), false);
        }
    }

    private void updateAndCleanLogs(PlayerSpeedData data) {
        for (LogEntry entry : data.logEntries) {
            entry.age++;
        }
        data.logEntries.removeIf(entry ->
                entry.age > (entry.isImportant ? LOG_LIFETIME_TICKS * 2 : LOG_LIFETIME_TICKS)
        );
    }

    private void addLogEntry(PlayerSpeedData data, String message, boolean isImportant) {
        data.logEntries.add(new LogEntry(message, isImportant));
    }

    private void logDamageDetails(Player player, float damage, double maxSpeed, double gForce,
                                  String damageType, Vec3 position, PlayerSpeedData data) {
        System.out.println("\n урон");
        System.out.println("Игрок: " + player.getName().getString());
        System.out.println("Урон: " + damage + " HP (" + (damage/2) + " сердец)");
        System.out.println("Макс. скорость: " + String.format("%.1f", maxSpeed) + " м/с");
        System.out.println("Перегрузка: " + String.format("%.1f", gForce) + " g");
        System.out.println("Тип: " + damageType);
        System.out.println("На земле: " + player.onGround());
        System.out.println("Пассажир: " + player.isPassenger());
        System.out.println("Позиция: " + String.format("(%.1f, %.1f, %.1f)", position.x, position.y, position.z));
        System.out.println("=====================================\n");
    }

    private String getPlayerState(Player player) {
        StringBuilder state = new StringBuilder();
        if (player.onGround()) state.append("G");
        if (player.isPassenger()) state.append("P");
        if (player.isFallFlying()) state.append("F");
        if (player.isSwimming()) state.append("S");
        if (player.isCrouching()) state.append("C");
        if (state.length() == 0) state.append("A");
        return state.toString();
    }

    private boolean detectCollisionImpact(Deque<Vec3> velocityHistory) {
        if (velocityHistory.size() < 5) return false;

        Vec3[] velocities = velocityHistory.toArray(new Vec3[0]);
        int recentCount = Math.min(5, velocities.length);

        Vec3 oldestRecent = velocities[velocities.length - recentCount];
        Vec3 newest = velocities[velocities.length - 1];

        double oldSpeed = oldestRecent.length();
        double newSpeed = newest.length();

        if (oldSpeed < MIN_IMPACT_SPEED || newSpeed < 1.0D) {
            return false;
        }

        double speedChange = newSpeed - oldSpeed;
        double speedChangePercent = Math.abs(speedChange) / oldSpeed;

        if (speedChange > -MIN_SPEED_CHANGE_MPS) {
            return false;
        }

        if (speedChangePercent < MIN_SPEED_CHANGE_PERCENT) {
            return false;
        }

        if (oldSpeed > 0.1D && newSpeed > 0.1D) {
            Vec3 oldDir = oldestRecent.normalize();
            Vec3 newDir = newest.normalize();
            double directionDot = oldDir.dot(newDir);

            if (directionDot > IMPACT_DIRECTION_CHANGE) {
                return false;
            }
        }

        return true;
    }

    private boolean detectFallImpact(Deque<Vec3> velocityHistory, Player player) {
        if (velocityHistory.size() < 3) return false;

        Vec3 current = velocityHistory.getLast();

        if (current.y >= 0.0D) return false;

        if (Math.abs(current.y) < 5.0D) return false;

        double horizontalSpeed = Math.sqrt(current.x * current.x + current.z * current.z);
        if (horizontalSpeed > 8.0D) return false;

        return true;
    }

    private double calculateCollisionGForce(Deque<Vec3> velocityHistory) {
        if (velocityHistory.size() < 3) return 0.0D;

        Vec3[] velocities = velocityHistory.toArray(new Vec3[0]);
        double maxDeceleration = 0.0D;

        for (int i = 0; i < velocities.length - 2; i++) {
            Vec3 v1 = velocities[i];
            Vec3 v2 = velocities[i + 1];

            double speed1 = v1.length();
            double speed2 = v2.length();

            if (speed2 < speed1) {
                double deceleration = (speed1 - speed2) * 20.0D;
                double gForce = deceleration / GRAVITY_ACCELERATION;
                maxDeceleration = Math.max(maxDeceleration, gForce);
            }
        }

        return maxDeceleration;
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        playerSpeedData.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        playerSpeedData.remove(event.getOriginal().getUUID());
    }

    private static boolean shouldApplyDamage(Player player) {
        return !player.isSpectator() &&
                !player.getAbilities().instabuild &&
                !player.isInvulnerable();
    }
}