package org.oldskooler.lootrestock.config;

import org.oldskooler.lootrestock.LootRestock;
import org.oldskooler.lootrestock.util.CronParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

    private static final long DEFAULT_RESET_TIME_VALUE = 7;
    private static final String DEFAULT_RESET_TIME_UNIT = "days";
    private static final boolean DEFAULT_ONLY_RESET_WHEN_EMPTY = true;
    private static final boolean DEFAULT_INCLUDE_BARRELS = false;

    private boolean includeBarrels;
    private boolean onlyResetWhenEmpty;

    // If cron is used, this flag is true and cronExpression contains the raw expression.
    private boolean useCron = false;
    private String cronExpression = null;
    private CronParser cronParser = null;

    // resetTimeMs semantics:
    // - If useCron == false: fixed interval in milliseconds (e.g., 7 days).
    // - If useCron == true: milliseconds until the next scheduled cron firing (nextValidTime - now).
    private long resetTimeMs;

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

            LootRestock.LOGGER.info("'{}' = {}", CONFIG_TIME_VALUE_KEY,
                    config.getProperty(CONFIG_TIME_VALUE_KEY));
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_TIME_UNIT_KEY,
                    config.getProperty(CONFIG_TIME_UNIT_KEY));
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_CRON_KEY,
                    config.getProperty(CONFIG_CRON_KEY));
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_ONLY_RESET_WHEN_EMPTY_KEY, onlyResetWhenEmpty);
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_INCLUDE_BARRELS_KEY, includeBarrels);
        }
    }

    private void createDefaultConfig(Properties config, Path configPath) throws IOException {
        config.setProperty(CONFIG_TIME_VALUE_KEY, String.valueOf(DEFAULT_RESET_TIME_VALUE));
        config.setProperty(CONFIG_TIME_UNIT_KEY, DEFAULT_RESET_TIME_UNIT);
        config.setProperty(CONFIG_ONLY_RESET_WHEN_EMPTY_KEY, String.valueOf(DEFAULT_ONLY_RESET_WHEN_EMPTY));
        config.setProperty(CONFIG_INCLUDE_BARRELS_KEY, String.valueOf(DEFAULT_INCLUDE_BARRELS));
        // Leave reset_cron blank by default
        config.setProperty(CONFIG_CRON_KEY, "");

        Files.createFile(configPath);
        try (OutputStream out = Files.newOutputStream(configPath)) {
            config.store(out, "LootRestock Configuration");
        }

        onlyResetWhenEmpty = DEFAULT_ONLY_RESET_WHEN_EMPTY;
        includeBarrels = DEFAULT_INCLUDE_BARRELS;
        useCron = false;
        cronExpression = null;
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
