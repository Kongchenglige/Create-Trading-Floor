package com.cak.trading_floor.registry;

import com.tterrag.registrate.util.entry.RegistryEntry;

import java.util.function.Supplier;

/**
 * Display sources for Create's DisplayLink functionality.
 * DISABLED for Create 0.5.1j compatibility.
 *
 * Re-enable for Create 6.0.0+ if DisplayLink support is needed.
 *
 * Note: DisplaySource-related imports removed to avoid NoClassDefFoundError
 * on Create 0.5.1j where DisplaySource parent class doesn't exist.
 */
public class TFDisplaySources {

    // Display sources disabled - DisplaySource class doesn't exist in Create 0.5.1j
    // Uncomment these lines for Create 6.0.0+ when needed
    // public static final RegistryEntry<?> TRADE_COMPLETED_COUNT;
    // public static final RegistryEntry<?> TRADE_PRODUCT_SUM;
    //
    // static {
    //     try {
    //         Class<?> displayClass = Class.forName("com.cak.trading_floor.content.trading_depot.displays.CurrentTradeCompletedCountDisplay");
    //         TRADE_COMPLETED_COUNT = simple("trade_completed_count", () -> displayClass.getDeclaredConstructor().newInstance());
    //     } catch (Exception e) {
    //         TRADE_COMPLETED_COUNT = null;
    //     }
    // }

    /**
     * Helper method for registering display sources.
     * Currently unused but kept for future Create 6.0.0+ support.
     * Note: This method uses reflection to avoid loading DisplaySource class at compile time.
     */
    @SuppressWarnings({"unused", "rawtypes"})
    private static RegistryEntry simple(String name, Supplier supplier) {
        // Use reflection to avoid compile-time dependency on DisplaySource
        try {
            Class.forName("com.simibubi.create.api.behaviour.display.DisplaySource");
            return (RegistryEntry) TFRegistry.REGISTRATE
                .displaySource(name, supplier)
                .register();
        } catch (ClassNotFoundException e) {
            // DisplaySource not available (Create 0.5.1j), return null
            return null;
        }
    }

    public static void register() {
        // Display sources disabled for Create 0.5.1j compatibility
    }

}
