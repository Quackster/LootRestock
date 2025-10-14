package org.oldskooler.lootrestock.config;

import org.oldskooler.lootrestock.LootRestock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration manager for the LootRestock mod.
 *
 * Configuration options:
 * <ul>
 *   <li><b>reset_time_value</b>: Number (e.g., 7)</li>
 *   <li><b>reset_time_unit</b>: Time unit (seconds, minutes, hours, days)</li>
 *   <li><b>only_reset_when_empty</b>: true/false (default: true)</li>
 *   <li><b>include_barrels</b>: true/false (default: false)</li>
 * </ul>
 */
public class ModConfig {
    private static final String CONFIG_FILE_NAME = "lootrestock.properties";
    private static final String CONFIG_TIME_VALUE_KEY = "reset_time_value";
    private static final String CONFIG_TIME_UNIT_KEY = "reset_time_unit";
    private static final String CONFIG_ONLY_RESET_WHEN_EMPTY_KEY = "only_reset_when_empty";
    private static final String CONFIG_INCLUDE_BARRELS_KEY = "include_barrels";

    private static final long DEFAULT_RESET_TIME_VALUE = 7;
    private static final String DEFAULT_RESET_TIME_UNIT = "days";
    private static final boolean DEFAULT_ONLY_RESET_WHEN_EMPTY = true;
    private static final boolean DEFAULT_INCLUDE_BARRELS = false;

    private boolean includeBarrels;
    private boolean onlyResetWhenEmpty;
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
                    DEFAULT_RESET_TIME_VALUE, DEFAULT_RESET_TIME_UNIT);
            timeValue = DEFAULT_RESET_TIME_VALUE;
            timeUnit = DEFAULT_RESET_TIME_UNIT;
        }

        resetTimeMs = convertToMilliseconds(timeValue, timeUnit);
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
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_ONLY_RESET_WHEN_EMPTY_KEY, onlyResetWhenEmpty);
            LootRestock.LOGGER.info("'{}' = {}", CONFIG_INCLUDE_BARRELS_KEY, includeBarrels);
        }
    }

    private void createDefaultConfig(Properties config, Path configPath) throws IOException {
        config.setProperty(CONFIG_TIME_VALUE_KEY, String.valueOf(DEFAULT_RESET_TIME_VALUE));
        config.setProperty(CONFIG_TIME_UNIT_KEY, DEFAULT_RESET_TIME_UNIT);
        config.setProperty(CONFIG_ONLY_RESET_WHEN_EMPTY_KEY, String.valueOf(DEFAULT_ONLY_RESET_WHEN_EMPTY));
        config.setProperty(CONFIG_INCLUDE_BARRELS_KEY, String.valueOf(DEFAULT_INCLUDE_BARRELS));

        Files.createFile(configPath);
        try (OutputStream out = Files.newOutputStream(configPath)) {
            config.store(out, "LootRestock Configuration");
        }

        onlyResetWhenEmpty = DEFAULT_ONLY_RESET_WHEN_EMPTY;
        includeBarrels = DEFAULT_INCLUDE_BARRELS;
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

    public long getResetTimeMs() {
        return resetTimeMs;
    }
}