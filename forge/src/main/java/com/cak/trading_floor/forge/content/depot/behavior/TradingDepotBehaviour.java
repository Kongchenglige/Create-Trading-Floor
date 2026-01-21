package com.cak.trading_floor.forge.content.depot.behavior;

import com.cak.trading_floor.content.trading_depot.behavior.CommonTradingDepotBehaviorAccess;
import com.cak.trading_floor.forge.content.depot.TradingDepotItemHandler;
import com.cak.trading_floor.foundation.TFLang;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.VersionedInventoryTrackerBehaviour;
import com.simibubi.create.foundation.item.ItemHelper;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING;

public class TradingDepotBehaviour extends BlockEntityBehaviour implements CommonTradingDepotBehaviorAccess {
    
    public static final BehaviourType<TradingDepotBehaviour> TYPE = new BehaviourType<>();
    public FilteringBehaviour filtering;
    
    final TradingDepotItemHandler itemHandler;
    final LazyOptional<TradingDepotItemHandler> itemHandlerLazyOptional;
    
    TransportedItemStack offer;
    List<ItemStack> result;
    List<TransportedItemStack> incoming;
    
    VersionedInventoryTrackerBehaviour invVersionTracker;

    boolean pruneEmptyStacksNextTick = false;

    //主动交易冷却时间（tick），避免每tick都尝试交易
    int tradingCooldown = 0;

    //村民缓存：减少频繁的实体查询
    @Nullable
    CachedVillagerInfo cachedVillagerInfo = null;

    //村民缓存有效期（tick，约5秒）
    static final int VILLAGER_CACHE_EXPIRY = 100;

    /**
     * 缓存的村民信息，避免频繁实体查询
     */
    public static class CachedVillagerInfo {
        final UUID villagerUuid;
        final BlockPos workstationPos;
        final long cacheTick;

        public CachedVillagerInfo(UUID villagerUuid, BlockPos workstationPos, long cacheTick) {
            this.villagerUuid = villagerUuid;
            this.workstationPos = workstationPos;
            this.cacheTick = cacheTick;
        }
    }
    
    public TradingDepotBehaviour(SmartBlockEntity be) {
        super(be);
        itemHandler = new TradingDepotItemHandler(this);
        itemHandlerLazyOptional = LazyOptional.of(() -> itemHandler);
        result = new ArrayList<>();
        incoming = new ArrayList<>();
    }
    
    @Override
    public void tick() {
        super.tick();

        Level world = blockEntity.getLevel();
        if (world == null) return;

        if (pruneEmptyStacksNextTick) {
            result = new ArrayList<>(
                result.stream()
                    .filter(stack -> !stack.isEmpty())
                    .toList()
            );
            pruneEmptyStacksNextTick = false;
        }

        for (Iterator<TransportedItemStack> iterator = incoming.iterator(); iterator.hasNext(); ) {
            TransportedItemStack ts = iterator.next();
            if (!tick(ts))
                continue;
            if (world.isClientSide && !blockEntity.isVirtual())
                continue;
            if (offer == null) {
                offer = ts;
            } else {
                if (!ItemHelper.canItemStackAmountsStack(offer.stack, ts.stack)) {
                    Vec3 vec = VecHelper.getCenterOf(blockEntity.getBlockPos());
                    Containers.dropItemStack(blockEntity.getLevel(), vec.x, vec.y + .5f, vec.z, ts.stack);
                } else {
                    offer.stack.grow(ts.stack.getCount());
                }
            }
            iterator.remove();
            blockEntity.notifyUpdate();
        }

        if (offer == null)
            return;
        tick(offer);

        //主动交易逻辑：在服务端且输出为空时尝试交易
        if (!world.isClientSide && tradingCooldown > 0) {
            tradingCooldown--;
        }

        if (!world.isClientSide && tradingCooldown <= 0 && isOutputEmpty()) {
            tryActiveTrading((ServerLevel) world);
        }
    }
    
    public void doPruneEmptyStacksNextTick() {
        pruneEmptyStacksNextTick = true;
    }
    
    protected boolean tick(TransportedItemStack input) {
        input.prevBeltPosition = input.beltPosition;
        input.prevSideOffset = input.sideOffset;
        float diff = .5f - input.beltPosition;
        if (diff > 1 / 512f) {
            if (diff > 1 / 32f && !BeltHelper.isItemUpright(input.stack))
                input.angle += 1;
            input.beltPosition += diff / 4f;
        }
        return diff < 1 / 16f;
    }
    
    public void addAdditionalBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new DirectBeltInputBehaviour(blockEntity)
            .allowingBeltFunnels()
            .setInsertionHandler(this::tryInsertingFromSide));
        behaviours.add(invVersionTracker = new VersionedInventoryTrackerBehaviour(blockEntity));
    }
    
    private ItemStack tryInsertingFromSide(TransportedItemStack transportedStack, Direction side, boolean simulate) {
        ItemStack inserted = transportedStack.stack;
        
        int size = transportedStack.stack.getCount();
        
        transportedStack = transportedStack.copy();
        
        transportedStack.beltPosition = side.getAxis().isVertical() ? .5f : 0;
        transportedStack.insertedFrom = side;
        transportedStack.prevSideOffset = transportedStack.sideOffset;
        transportedStack.prevBeltPosition = transportedStack.beltPosition;
        
        ItemStack remainder = insert(transportedStack, simulate);
        if (remainder.getCount() != size)
            blockEntity.notifyUpdate();
        
        return remainder;
    }
    
    public int getPresentStackSize() {
        int cumulativeStackSize = 0;
        cumulativeStackSize += getOfferStack().getCount();
        for (ItemStack stack : result)
            cumulativeStackSize += stack
                .getCount();
        return cumulativeStackSize;
    }
    
    public int getRemainingSpace() {
        int cumulativeStackSize = getPresentStackSize();
        for (TransportedItemStack transportedItemStack : incoming)
            cumulativeStackSize += transportedItemStack.stack.getCount();
        return 64 - cumulativeStackSize;
    }
    
    public ItemStack insert(TransportedItemStack input, boolean simulate) {
        int remainingSpace = getRemainingSpace();
        ItemStack inserted = input.stack;
        if (remainingSpace <= 0)
            return inserted;
        if (this.offer != null && !this.offer.stack.isEmpty() && !ItemHandlerHelper.canItemStacksStack(this.offer.stack, inserted))
            return inserted;
        
        ItemStack returned = ItemStack.EMPTY;
        if (remainingSpace < inserted.getCount()) {
            returned = ItemHandlerHelper.copyStackWithSize(input.stack, inserted.getCount() - remainingSpace);
            if (!simulate) {
                TransportedItemStack copy = input.copy();
                copy.stack.setCount(remainingSpace);
                if (this.offer != null && !this.offer.stack.isEmpty())
                    incoming.add(copy);
                else
                    this.offer = copy;
            }
        } else {
            if (!simulate) {
                if (this.offer != null && !this.offer.stack.isEmpty())
                    incoming.add(input);
                else
                    this.offer = input;
            }
        }
        return returned;
    }
    
    public boolean isEmpty() {
        return offer == null && isOutputEmpty();
    }
    
    public boolean isOutputEmpty() {
        for (ItemStack stack : result)
            if (!stack.isEmpty())
                return false;
        return true;
    }
    
    @Override
    public void destroy() {
        super.destroy();
        Level level = getWorld();
        BlockPos pos = getPos();
        ItemHelper.dropContents(level, pos, itemHandler);
    }
    
    @Override
    public void unload() {
        if (itemHandlerLazyOptional != null)
            itemHandlerLazyOptional.invalidate();
    }
    
    @Override
    public void read(CompoundTag nbt, boolean clientPacket) {
        offer = null;
        if (nbt.contains("Input"))
            offer = TransportedItemStack.read(nbt.getCompound("Input"));

        int outputCount = nbt.getInt("OutputCount");
        result = new ArrayList<>(outputCount);

        for (int i = 0; i < outputCount; i++) {
            result.add(ItemStack.of(nbt.getCompound("Output" + i)));
        }

        ListTag list = nbt.getList("Incoming", Tag.TAG_COMPOUND);
        incoming = NBTHelper.readCompoundList(list, TransportedItemStack::read);

        //读取主动交易相关数据
        tradingCooldown = nbt.getInt("TradingCooldown");
        //村民缓存不需要持久化，重启后重新获取即可
    }
    
    @Override
    public void write(CompoundTag nbt, boolean clientPacket) {
        if (offer != null)
            nbt.put("Input", offer.serializeNBT());

        nbt.putInt("OutputCount", result.size());
        for (int i = 0; i < result.size(); i++) {
            nbt.put("Output" + i, result.get(i).save(new CompoundTag()));
        }

        if (!incoming.isEmpty())
            nbt.put("Incoming", NBTHelper.writeCompoundList(incoming, TransportedItemStack::serializeNBT));

        //保存主动交易相关数据
        nbt.putInt("TradingCooldown", tradingCooldown);
        //村民缓存不需要持久化
    }
    
    public LazyOptional<TradingDepotItemHandler> getItemHandler() {
        return itemHandlerLazyOptional;
    }
    
    public ItemStack getOfferStack() {
        return offer == null ? ItemStack.EMPTY : offer.stack;
    }
    
    public void setOfferStack(TransportedItemStack input) {
        this.offer = input;
    }
    
    public void setOfferStack(ItemStack input) {
        if (this.offer != null)
            this.offer.stack = input;
        else
            this.offer = new TransportedItemStack(input);
    }
    
    
    public void removeOfferStack() {
        this.offer = null;
    }
    
    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }
    
    public void combineOutputs() {
        List<ItemStack> result = new ArrayList<>();
        
        for (ItemStack stack : this.result) {
            
            for (ItemStack other : result) {
                if (!ItemHandlerHelper.canItemStacksStack(stack, other)) continue;
                
                int newCount = Math.min(other.getCount() + stack.getCount(), other.getMaxStackSize());
                int filledCount = newCount - other.getCount();
                
                other.setCount(newCount);
                stack.setCount(stack.getCount() - filledCount);
            }
            
            if (!stack.isEmpty())
                result.add(stack);
        }
        
        this.result = result;
    }
    
    public boolean canBeUsedFor(MerchantOffer offer) {
        return filtering.test(offer.getResult());
    }
    
    public void addContentsToTooltip(List<Component> tooltip) {
        TFLang.translate("tooltip.trading_depot.contents")
            .forGoggles(tooltip);
        
        if (offer != null && !offer.stack.isEmpty()) {
            TFLang.translate("tooltip.trading_depot.contents.input")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip, 1);
            
            TFLang.itemStack(offer.stack)
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip, 2);
        }
        
        if (!result.isEmpty()) {
            TFLang.translate("tooltip.trading_depot.contents.output")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip, 1);
            
            for (ItemStack stack : result) {
                TFLang.itemStack(stack)
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip, 2);
            }
        }
    }
    
    public List<ItemStack> getResults() {
        return result;
    }
    
    public void invalidate() {
        itemHandlerLazyOptional.invalidate();
    }
    
    public void resetInv() {
        invVersionTracker.reset();
    }
    
    public TransportedItemStack getOffer() {
        return offer;
    }
    
    public List<TransportedItemStack> getIncoming() {
        return incoming;
    }
    
    public TradingDepotItemHandler getRealItemHandler() {
        return itemHandler;
    }
    
    public void spinOfferOrSomething() {
        offer.angle += (int) ((Math.random() * 10 + 10) * (Math.random() > 0.5f ? -1 : 1));
    }

    /**
     * 主动交易：检测周围8格内的村民，如果村民的工作站是这个depot指向的方块，则直接交易
     * 优化版本：使用缓存减少实体查询，智能冷却时间
     */
    protected void tryActiveTrading(ServerLevel level) {
        BlockPos depotPos = blockEntity.getBlockPos();
        Direction facing = blockEntity.getBlockState().getValue(FACING);
        BlockPos workstationPos = depotPos.relative(facing);

        //提前检查：如果自己没有输入物品，直接跳过
        if (offer == null || offer.stack.isEmpty()) {
            tradingCooldown = 40; //没有输入时，2秒后再检查
            return;
        }

        long currentTick = level.getGameTime();

        //优先使用缓存的村民
        Villager targetVillager = null;

        if (cachedVillagerInfo != null &&
            currentTick - cachedVillagerInfo.cacheTick < VILLAGER_CACHE_EXPIRY &&
            cachedVillagerInfo.workstationPos.equals(workstationPos)) {

            //缓存有效，尝试获取缓存的村民实体
            targetVillager = findVillagerByUuid(level, cachedVillagerInfo.villagerUuid);
        }

        //缓存无效或村民不存在，重新搜索
        if (targetVillager == null) {
            targetVillager = findAndCacheVillager(level, workstationPos, currentTick);
        }

        //如果没找到合适的村民，设置冷却时间
        if (targetVillager == null) {
            tradingCooldown = 40; //没有村民时，2秒后再检查
            cachedVillagerInfo = null; //清除无效缓存
            return;
        }

        //收集所有连接到该工作站的depot
        List<CommonTradingDepotBehaviorAccess> allDepots = collectAllTradingDepotsAtWorkstation(level, workstationPos);

        if (allDepots.isEmpty()) {
            tradingCooldown = 40;
            return;
        }

        //检查是否有至少一个depot有输入物品
        boolean hasAnyInput = allDepots.stream()
            .anyMatch(depot -> depot.getOffer() != null && !depot.getOffer().stack.isEmpty());

        if (!hasAnyInput) {
            tradingCooldown = 40; //没有输入时，2秒后再检查
            return;
        }

        //执行交易（调用BlockEntity的交易方法）
        if (blockEntity instanceof com.cak.trading_floor.forge.content.depot.TradingDepotBlockEntity depotBE) {
            depotBE.tryTradeWith(targetVillager, allDepots);

            //交易成功后清除缓存，允许下次交易其他村民
            cachedVillagerInfo = null;

            //根据是否有更多输出调整冷却时间
            boolean hasMoreOutput = !isOutputEmpty();
            tradingCooldown = hasMoreOutput ? 80 : 40; //有输出4秒，无输出2秒
        }
    }

    /**
     * 根据UUID查找村民实体
     */
    @Nullable
    private Villager findVillagerByUuid(ServerLevel level, UUID uuid) {
        //优先从附近实体中查找
        BlockPos depotPos = blockEntity.getBlockPos();
        List<Villager> nearbyVillagers = level.getEntitiesOfClass(
            Villager.class,
            new net.minecraft.world.phys.AABB(depotPos).inflate(8)
        );

        for (Villager villager : nearbyVillagers) {
            if (villager.getUUID().equals(uuid)) {
                return villager;
            }
        }

        return null;
    }

    /**
     * 查找并缓存合适的村民
     */
    @Nullable
    private Villager findAndCacheVillager(ServerLevel level, BlockPos workstationPos, int currentTick) {
        BlockPos depotPos = blockEntity.getBlockPos();

        //检测周围8格内的村民
        List<Villager> nearbyVillagers = level.getEntitiesOfClass(
            Villager.class,
            new net.minecraft.world.phys.AABB(depotPos).inflate(8)
        );

        for (Villager villager : nearbyVillagers) {
            //检查村民是否有工作站记忆
            Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isEmpty()) continue;

            //检查村民的工作站是否是这个depot指向的方块
            if (!jobSite.get().pos().equals(workstationPos)) continue;

            //检查村民的维度是否匹配
            if (!jobSite.get().dimension().equals(level.dimension())) continue;

            //找到合适的村民，缓存信息
            cachedVillagerInfo = new CachedVillagerInfo(villager.getUUID(), workstationPos, currentTick);
            return villager;
        }

        return null;
    }

    /**
     * 收集所有连接到指定工作站的trading depot
     */
    protected List<CommonTradingDepotBehaviorAccess> collectAllTradingDepotsAtWorkstation(ServerLevel level, BlockPos workstationPos) {
        List<CommonTradingDepotBehaviorAccess> depots = new ArrayList<>();

        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            BlockPos depotPos = workstationPos.relative(direction);
            var blockEntity = level.getBlockEntity(depotPos);

            if (blockEntity instanceof com.cak.trading_floor.forge.content.depot.TradingDepotBlockEntity depotBE) {
                //检查depot是否指向工作站
                Direction depotFacing = depotBE.getBlockState().getValue(FACING);
                if (depotFacing == direction) {
                    depots.add(depotBE.getCommonTradingDepotBehaviour());
                }
            }
        }

        return depots;
    }

}
