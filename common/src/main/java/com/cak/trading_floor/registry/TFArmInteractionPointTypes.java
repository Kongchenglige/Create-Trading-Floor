package com.cak.trading_floor.registry;

import com.cak.trading_floor.TradingFloor;
import com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;

public class TFArmInteractionPointTypes {

    public static final TradingDepotType TRADING_DEPOT_TYPE = new TradingDepotType();

    public static class TradingDepotType extends ArmInteractionPointType {

        @Override
        public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
            return TFRegistry.TRADING_DEPOT.has(state);
        }

        @Override
        public ArmInteractionPoint createPoint(Level level, BlockPos pos, BlockState state) {
            return new AllArmInteractionPointTypes.DeployerPoint(this, level, pos, state);
        }

    }

    /**
     * Register the trading depot type for mechanical arm interaction.
     * This method supports both Create 0.5.1.x and Create 6.0.0+ APIs.
     */
    public static void register() {
        try {
            // Try Create 6.0.0+ API first (Forge Registry)
            Class<?> registriesClass = Class.forName("com.simibubi.create.api.registry.CreateBuiltInRegistries");
            Object registry = registriesClass.getField("ARM_INTERACTION_POINT_TYPE").get(null);

            // Get Registry.register method
            Class<?> registryClass = Class.forName("net.minecraft.core.Registry");
            Method registerMethod = registryClass.getMethod("register", Class.forName("net.minecraft.core.Registry"), Object.class, Object.class);

            // Call Registry.register(CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE, name, type)
            registerMethod.invoke(null, registry, TradingFloor.asResource("trading_depot"), TRADING_DEPOT_TYPE);

            TradingFloor.LOGGER.info("Registered ArmInteractionPointType using Create 6.0.0+ API");

        } catch (Exception e) {
            // Fallback to Create 0.5.1.x API (direct register method)
            try {
                Method registerMethod = ArmInteractionPointType.class.getMethod("register", ArmInteractionPointType.class);
                registerMethod.invoke(null, TRADING_DEPOT_TYPE);
                TradingFloor.LOGGER.info("Registered ArmInteractionPointType using Create 0.5.1.x API");
            } catch (Exception ex) {
                TradingFloor.LOGGER.error("Failed to register ArmInteractionPointType", ex);
                throw new RuntimeException("Failed to register ArmInteractionPointType for both Create 0.5.1.x and 6.0.0+ APIs", ex);
            }
        }
    }

}
