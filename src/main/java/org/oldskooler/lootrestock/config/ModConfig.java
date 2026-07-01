package org.oldskooler.lootrestock.config;

import org.oldskooler.lootrestock.LootRestock;
import org.oldskooler.lootrestock.util.CronParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Properties;

/**
 * Configuration manager for the LootRestock mod.
 *
 * Configuration options:
 * <ul>
 *   <li><b>reset_time_value</b>: Number (e.g., 7)</li>
 *   <li><b>reset_time_unit</b>: Time unit (seconds, minutes, hours, days)</li>
 *   <li><b>reset_cron</b>: Optional cron expression (e.g. "0 0/30 * * * ?" or standard 5-field "0 0 * * *")</li>
 *   <li><b>only_reset_when_empty</b>: true/false (default: true)</li>
 *   <li><b>include_barrels</b>: true/false (default: false)</li>
 *   <li><b>allow_chest_breaking</b>: Who can break chests - "op_only", "anyone", or "no_one" (default: op_only)</li>
 *   <li><b>require_crouch_to_break</b>: true/false (default: true)</li>
 *   <li><b>crouch_break_seconds</b>: Seconds players must keep breaking while crouched (default: 4)</li>
 * </ul>
 *
 * Notes:
 * - If reset_cron is present and valid, it takes precedence over reset_time_value/reset_time_unit.
 * - Quartz's CronExpression will be used reflectively if available on the classpath. If not available,
 *   cron parsing will be skipped and the numeric time-based config will be used.
 */
public class ModConfig {
    private static final String CONFIG_FILE_NAME = "lootrestock.properties";
    private static final String CONFIG_TIME_VALUE_KEY = "reset_time_value";
    private static final String CONFIG_TIME_UNIT_KEY = "reset_time_unit";
    private static final String CONFIG_CRON_KEY = "reset_cron";
    private static final String CONFIG_ONLY_RESET_WHEN_EMPTY_KEY = "only_reset_when_empty";
    private static final String CONFIG_INCLUDE_BARRELS_KEY = "include_barrels";
    private static final String CONFIG_ALLOW_CHEST_BREAKING_KEY = "allow_chest_breaking";
    private static final String CONFIG_REQUIRE_CROUCH_TO_BREAK_KEY = "require_crouch_to_break";
    private static final String CONFIG_CROUCH_BREAK_SECONDS_KEY = "crouch_break_seconds";

    private static final long DEFAULT_RESET_TIME_VALUE = 7;
    private static final String DEFAULT_RESET_TIME_UNIT = "days";
    private static final boolean DEFAULT_ONLY_RESET_WHEN_EMPTY = true;
    private static final boolean DEFAULT_INCLUDE_BARRELS = false;
    private static final ChestBreakingPermission DEFAULT_CHEST_BREAKING = ChestBreakingPermission.OP_ONLY;
    private static final boolean DEFAULT_REQUIRE_CROUCH_TO_BREAK = true;
    private static final long DEFAULT_CROUCH_BREAK_SECONDS = 4;

    private boolean includeBarrels;
    private boolean onlyResetWhenEmpty;
    private ChestBreakingPermission chestBreakingPermission;
    private boolean requireCrouchToBreak;
    private long crouchBreakMs;

    // If cron is used, this flag is true and cronExpression contains the raw expression.
    private boolean useCron = false;
    private String cronExpression = null;
    private CronParser cronParser = null;

    // resetTimeMs semantics:
    // - If useCron == false: fixed interval in milliseconds (e.g., 7 days).
    // - If useCron == true: milliseconds until the next scheduled cron firing (nextValidTime - now).
    private long resetTimeMs;

    /**
     * Enum representing who can break chests with loot tables.
     */
    public enum ChestBreakingPermission {
        OP_ONLY,    // Only server operators can break chests
        ANYONE,     // Anyone can break chests
        NO_ONE;     // No one can break chests (protected)

        public static ChestBreakingPermission fromString(String value) {
            if (value == null) return DEFAULT_CHEST_BREAKING;

            return switch (value.toLowerCase().trim()) {
                case "op_only", "op" -> OP_ONLY;
                case "anyone", "all" -> ANYONE;
                case "no_one", "none", "nobody" -> NO_ONE;
                default -> {
                    LootRestock.LOGGER.warn("Invalid chest breaking permission '{}'. Using default: {}",
                            value, DEFAULT_CHEST_BREAKING);
                    yield DEFAULT_CHEST_BREAKING;
                }
            };
        }

        @Override
        public String toString() {
            return switch (this) {
                case OP_ONLY -> "op_only";
                case ANYONE -> "anyone";
                case NO_ONE -> "no_one";
            };
        }
    }

    /**
     * Loads configuration from the config file.
     * Creates a default config file if one doesn't exist.
     */
    public void load() {
        Properties config = new Properties();
        Path configPath = Path.of(CONFIG_FILE_NAME);

        long timeValue = DEFAULT_RESET_TIME_VALUE;
        String timeUnit = DEFAULT_RESET_TIME_UNIT;

        try {
            if (Files.exists(configPath)) {
                loadExistingConfig(config, configPath);

                // Check cron first (optional). If present and parseable, it will take precedence.
                String cron = config.getProperty(CONFIG_CRON_KEY, "").trim();
                if (!cron.isEmpty()) {
                    boolean cronParsed = tryUseCron(cron);
                    if (cronParsed) {
                        // cron parsing succeeded and resetTimeMs has been set to next delay
                        LootRestock.LOGGER.info("Using cron schedule '{}' for resets (next in {} ms)", cron, resetTimeMs);
                        return; // finished loading config, cron is used
                    } else {
                        LootRestock.LOGGER.warn("Cron '{}' present but could not be used; falling back to time value/unit.", cron);
                    }
                }

                // Cron not present or not usable — fall back to numeric timeValue/timeUnit
                timeValue = Long.parseLong(config.getProperty(CONFIG_TIME_VALUE_KEY,
                        String.valueOf(DEFAULT_RESET_TIME_VALUE)));
                timeUnit = config.getProperty(CONFIG_TIME_UNIT_KEY, DEFAULT_RESET_TIME_UNIT).toLowerCase();

                if (timeValue <= 0) {
                    throw new IllegalArgumentException(CONFIG_TIME_VALUE_KEY + " must be greater than 0");
                }
            } else {
                createDefaultConfig(config, configPath);
            }
        } catch (IOException | NumberFormatException e) {
            LootRestock.LOGGER.warn("Failed to load or parse config. Using defaults: {} {}",
                    DEFAULT_RESET_TIME_VALUE, DEFAULT_RESET_TIME_UNIT, e);
            timeValue = DEFAULT_RESET_TIME_VALUE;
            timeUnit = DEFAULT_RESET_TIME_UNIT;
        }

        // Convert numeric interval to milliseconds
        resetTimeMs = convertToMilliseconds(timeValue, timeUnit);
        useCron = false;
        cronExpression = null;
        LootRestock.LOGGER.info("Chest reset time set to {} {} ({} ms)", timeValue, timeUnit, resetTimeMs);
    }

    private void loadExistingConfig(Properties config, Path configPath) throws IOException {
        try (InputStream in = Files.newInputStream(configPath)) {
            config.load(in);

            onlyResetWhenEmpty = Boolean.parseBoolean(
                    config.getProperty(CONFIG_ONLY_RESET_WHEN_EMPTY_KEY,
                            String.valueOf(DEFAULT_ONLY_RESET_WHEN_EMPTY))
            );
            includeBarrels = Boolean.parseBoolean(
                    config.getProperty(CONFIG_INCLUDE_BARRELS_KEY,
                            String.valueOf(DEFAULT_INCLUDE_BARRELS))
            );
            chestBreakingPermission = ChestBreakingPermission.fromString(
                    config.getProperty(CONFIG_ALLOW_CHEST_BREAKING_KEY,
                            DEFAULT_CHEST_BREAKING.toString())
            );
            requireCrouchToBreak = Boolean.parseBoolean(
                    config.getProperty(CONFIG_REQUIRE_CROUCH_TO_BREAK_KEY,
                            String.valueOf(DEFAULT_REQUIRE_CROUCH_TO_BREAK))
            );
            crouchBreakMs = parseCrouchBreakMs(config.getProperty(CONFIG_CROUCH_BREAK_SECONDS_KEY,
                    String.valueOf(DEFAULT_CROUCH_BREAK_SECONDS)));

            LootRestock.LOGGER.info("'{}' = {}", CONFIG_TIME_VALUE_KEY,
                    config.getProperty(CONFIG_TIME_VALUE_KEY));
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_TIME_UNIT_KEY,
                    config.getProperty(CONFIG_TIME_UNIT_KEY));
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_CRON_KEY,
                    config.getProperty(CONFIG_CRON_KEY));
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_ONLY_RESET_WHEN_EMPTY_KEY, onlyResetWhenEmpty);
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_INCLUDE_BARRELS_KEY, includeBarrels);
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_ALLOW_CHEST_BREAKING_KEY, chestBreakingPermission);
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_REQUIRE_CROUCH_TO_BREAK_KEY, requireCrouchToBreak);
            LootRestock.LOGGER.info("'{}' = {} ms", CONFIG_CROUCH_BREAK_SECONDS_KEY, crouchBreakMs);
        }
    }

    private void createDefaultConfig(Properties config, Path configPath) throws IOException {
        Files.createFile(configPath);

        // Write config file with comments manually for better formatting
        try (OutputStream out = Files.newOutputStream(configPath)) {
            StringBuilder sb = new StringBuilder();
            sb.append("# LootRestock Configuration\n");
            sb.append("# \n");
            sb.append("# This file controls how loot chests and barrels respawn in your world.\n");
            sb.append("\n");
            sb.append("# ==================== RESET TIMING ====================\n");
            sb.append("# How often should loot containers reset?\n");
            sb.append("# Use either the time value/unit combo OR the cron expression below.\n");
            sb.append("# \n");
            sb.append("# Reset time value (positive number)\n");
            sb.append(CONFIG_TIME_VALUE_KEY).append("=").append(DEFAULT_RESET_TIME_VALUE).append("\n");
            sb.append("# \n");
            sb.append("# Reset time unit (seconds, minutes, hours, days)\n");
            sb.append(CONFIG_TIME_UNIT_KEY).append("=").append(DEFAULT_RESET_TIME_UNIT).append("\n");
            sb.append("# \n");
            sb.append("# Advanced: Cron expression for scheduled resets (optional)\n");
            sb.append("# If set, this takes precedence over reset_time_value/reset_time_unit\n");
            sb.append("# Examples:\n");
            sb.append("#   \"0 0 * * *\"     - Daily at midnight\n");
            sb.append("#   \"0 0 * * 0\"     - Weekly on Sunday at midnight\n");
            sb.append("#   \"0 */6 * * *\"   - Every 6 hours\n");
            sb.append("#   \"0 0 1 * *\"     - Monthly on the 1st at midnight\n");
            sb.append(CONFIG_CRON_KEY).append("=\n");
            sb.append("\n");
            sb.append("# ==================== RESET BEHAVIOR ====================\n");
            sb.append("# Only reset containers when they are completely empty?\n");
            sb.append("# If true, containers with any items left will not reset\n");
            sb.append("# If false, containers will reset at the scheduled time regardless of contents\n");
            sb.append(CONFIG_ONLY_RESET_WHEN_EMPTY_KEY).append("=").append(DEFAULT_ONLY_RESET_WHEN_EMPTY).append("\n");
            sb.append("\n");
            sb.append("# ==================== CONTAINER TYPES ====================\n");
            sb.append("# Should barrels be included in the reset system?\n");
            sb.append("# If true, barrels with loot tables will reset like chests\n");
            sb.append("# If false, only chests will reset\n");
            sb.append(CONFIG_INCLUDE_BARRELS_KEY).append("=").append(DEFAULT_INCLUDE_BARRELS).append("\n");
            sb.append("\n");
            sb.append("# ==================== PROTECTION ====================\n");
            sb.append("# Who can break loot containers?\n");
            sb.append("# Options:\n");
            sb.append("#   op_only  - Only server operators can break chests (default)\n");
            sb.append("#   anyone   - Anyone can break chests\n");
            sb.append("#   no_one   - No one can break chests (fully protected)\n");
            sb.append(CONFIG_ALLOW_CHEST_BREAKING_KEY).append("=").append(DEFAULT_CHEST_BREAKING.toString()).append("\n");
            sb.append("# \n");
            sb.append("# Require players with break permission to crouch before removing a loot container?\n");
            sb.append(CONFIG_REQUIRE_CROUCH_TO_BREAK_KEY).append("=").append(DEFAULT_REQUIRE_CROUCH_TO_BREAK).append("\n");
            sb.append("# \n");
            sb.append("# How long must a permitted player keep breaking while crouched?\n");
            sb.append("# This makes accidental removal harder. Set to 0 for normal break speed.\n");
            sb.append(CONFIG_CROUCH_BREAK_SECONDS_KEY).append("=").append(DEFAULT_CROUCH_BREAK_SECONDS).append("\n");

            out.write(sb.toString().getBytes());
        }

        onlyResetWhenEmpty = DEFAULT_ONLY_RESET_WHEN_EMPTY;
        includeBarrels = DEFAULT_INCLUDE_BARRELS;
        chestBreakingPermission = DEFAULT_CHEST_BREAKING;
        requireCrouchToBreak = DEFAULT_REQUIRE_CROUCH_TO_BREAK;
        crouchBreakMs = DEFAULT_CROUCH_BREAK_SECONDS * 1000L;
        useCron = false;
        cronExpression = null;
    }

    private long parseCrouchBreakMs(String value) {
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0) {
                throw new IllegalArgumentException(CONFIG_CROUCH_BREAK_SECONDS_KEY + " must be 0 or greater");
            }
            return seconds * 1000L;
        } catch (RuntimeException e) {
            LootRestock.LOGGER.warn("Invalid '{}' value '{}'. Using default: {} seconds",
                    CONFIG_CROUCH_BREAK_SECONDS_KEY, value, DEFAULT_CROUCH_BREAK_SECONDS);
            return DEFAULT_CROUCH_BREAK_SECONDS * 1000L;
        }
    }

    /**
     * Attempt to use cron expressions.
     *
     * @param cron the cron expression string
     * @return true if cron was successfully parsed and next run time computed; false otherwise
     */
    private boolean tryUseCron(String cron) {
        // Defensive: null/empty check
        if (cron == null || cron.trim().isEmpty()) return false;

        try {
            this.cronExpression = cron.trim();
            this.cronParser = new CronParser(this.cronExpression);

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime nextExecution = this.cronParser.getNextExecution(now);

            // Calculate seconds until next execution
            long delay = Duration.between(now, nextExecution).getSeconds() * 1000;

            this.useCron = true;
            this.cronExpression = cron;
            this.resetTimeMs = delay;
            return true;
        } catch (Exception e) {
            LootRestock.LOGGER.warn("Unexpected error while trying to parse cron expression '{}': {}", cron, e.toString());
            return false;
        }
    }

    private long convertToMilliseconds(long timeValue, String timeUnit) {
        return switch (timeUnit) {
            case "seconds" -> timeValue * 1000L;
            case "minutes" -> timeValue * 60 * 1000L;
            case "hours" -> timeValue * 60 * 60 * 1000L;
            case "days" -> timeValue * 24 * 60 * 60 * 1000L;
            default -> {
                LootRestock.LOGGER.warn("Unrecognized time unit '{}'. Defaulting to days.", timeUnit);
                yield timeValue * 24 * 60 * 60 * 1000L;
            }
        };
    }

    public boolean includeBarrels() {
        return includeBarrels;
    }

    public boolean onlyResetWhenEmpty() {
        return onlyResetWhenEmpty;
    }

    /**
     * Returns the chest breaking permission setting.
     */
    public ChestBreakingPermission getChestBreakingPermission() {
        return chestBreakingPermission;
    }

    public boolean requireCrouchToBreak() {
        return requireCrouchToBreak;
    }

    public long getCrouchBreakMs() {
        return crouchBreakMs;
    }

    /**
     * Returns whether a cron schedule is being used.
     * If true, getResetTimeMs() returns the milliseconds until the next cron occurrence.
     */
    public boolean isCronUsed() {
        return useCron;
    }

    /**
     * If cron is used, returns the cron expression string; otherwise null.
     */
    public String getCronExpression() {
        return cronExpression;
    }

    public CronParser getCronParser() {
        return cronParser;
    }

    /**
     * Returns the configured reset time in milliseconds.
     * - If cron is used: milliseconds until the next scheduled cron fire.
     * - If cron is not used: the fixed interval in milliseconds.
     */
    public long getResetTimeMs() {
        return resetTimeMs;
    }
}
