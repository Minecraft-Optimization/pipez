package de.maxhenkel.pipez.blocks.tileentity.types;

import de.maxhenkel.corelib.item.ItemUtils;
import de.maxhenkel.pipez.*;
import de.maxhenkel.pipez.blocks.ModBlocks;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.PipeTileEntity;
import de.maxhenkel.pipez.blocks.tileentity.UpgradeTileEntity;
import de.maxhenkel.pipez.datacomponents.ItemData;
import de.maxhenkel.pipez.items.ModItems;
import de.maxhenkel.pipez.utils.ComponentUtils;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ItemPipeType extends PipeType<Item, ItemData> {

    public static final ItemPipeType INSTANCE = new ItemPipeType();

    @Override
    public BlockCapability<?, Direction> getCapability() {
        return Capabilities.ItemHandler.BLOCK;
    }

    @Override
    public Filter<?, Item> createFilter() {
        return new ItemFilter();
    }

    @Override
    public UpgradeTileEntity.Distribution getDefaultDistribution() {
        return UpgradeTileEntity.Distribution.NEAREST;
    }

    @Override
    public String getTranslationKey() {
        return "tooltip.pipez.item";
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(ModBlocks.ITEM_PIPE.get());
    }

    @Override
    public Component getTransferText(@Nullable Upgrade upgrade) {
        return Component.translatable("tooltip.pipez.rate.item", getRate(upgrade), getSpeed(upgrade));
    }

    @Override
    public void tick(PipeLogicTileEntity tileEntity) {
        for (Direction side : Direction.values()) {
            if (!tileEntity.shouldTickWithBackoff(side, this, getSpeed(tileEntity, side))) {
                continue;
            }
            if (!tileEntity.isExtracting(side)) {
                continue;
            }
            if (!tileEntity.shouldWork(side, this)) {
                continue;
            }
            PipeTileEntity.Connection extractingConnection = tileEntity.getExtractingConnection(side);
            if (extractingConnection == null) {
                tileEntity.onTransferFailed(side, this);
                continue;
            }
            IItemHandler itemHandler = extractingConnection.getItemHandler();
            if (itemHandler == null) {
                tileEntity.onTransferFailed(side, this);
                continue;
            }

            List<PipeTileEntity.Connection> connections = tileEntity.getSortedConnections(side, this);

            boolean success;
            if (tileEntity.getDistribution(side, this).equals(UpgradeTileEntity.Distribution.ROUND_ROBIN)) {
                success = insertEqually(tileEntity, side, connections, itemHandler);
            } else {
                success = insertOrdered(tileEntity, side, connections, itemHandler);
            }

            if (success) {
                tileEntity.onTransferSuccess(side, this);
            } else {
                tileEntity.onTransferFailed(side, this);
            }
        }
    }

    protected boolean insertEqually(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IItemHandler itemHandler) {
        if (connections.isEmpty()) {
            return false;
        }
        int itemsToTransfer = getRate(tileEntity, side);
        int initialItems = itemsToTransfer;
        int connectionCount = connections.size();

        // Loop invariants, resolved once per operation instead of once per moved item
        HolderLookup.Provider provider = tileEntity.getLevel().registryAccess();
        List<Filter<?, ?>> filters = tileEntity.getFilters(side, this);
        boolean blacklist = tileEntity.getFilterMode(side, this).equals(UpgradeTileEntity.FilterMode.BLACKLIST);
        int sourceSlots = itemHandler.getSlots();
        long gameTime = tileEntity.getLevel().getGameTime();
        boolean backoff = Main.SERVER_CONFIG.backoffEnabled.get();

        // Source slots below this index are known to be empty (we only ever extract during an operation)
        int firstNonEmptySlot = 0;

        // Destination state is resolved lazily on the first visit of each connection
        Target[] targets = new Target[connectionCount];
        int activeConnections = connectionCount;
        int p = tileEntity.getRoundRobinIndex(side, this) % connectionCount;

        while (itemsToTransfer > 0 && activeConnections > 0) {
            Target target = targets[p];
            if (target == null) {
                target = targets[p] = new Target(connections.get(p), side, gameTime, backoff);
            }
            if (!target.done) {
                boolean hasInserted = false;

                if (target.handler != null) {
                    // Move an equal share per visit instead of a single item
                    int chunk = (itemsToTransfer + activeConnections - 1) / activeConnections;
                    for (int j = firstNonEmptySlot; j < sourceSlots; j++) {
                        ItemStack simulatedExtract = itemHandler.extractItem(j, chunk, true);
                        if (simulatedExtract.isEmpty()) {
                            if (j == firstNonEmptySlot) {
                                firstNonEmptySlot++;
                            }
                            continue;
                        }
                        // Don't offer an item this destination already refused during this operation
                        if (target.hasRejected(simulatedExtract)) {
                            continue;
                        }
                        if (canInsert(provider, target.connection, simulatedExtract, filters) == blacklist) {
                            target.reject(simulatedExtract);
                            continue;
                        }
                        ItemStack stack = insertItem(target.handler, target.slots, simulatedExtract);
                        int insertedAmount = simulatedExtract.getCount() - stack.getCount();
                        if (insertedAmount > 0) {
                            itemsToTransfer -= insertedAmount;
                            itemHandler.extractItem(j, insertedAmount, false);
                            target.inserted = true;
                            hasInserted = true;
                            break;
                        }
                        target.refused = true;
                        target.reject(simulatedExtract);
                    }
                }

                if (!hasInserted) {
                    target.done = true;
                    activeConnections--;
                }
            }
            p = (p + 1) % connectionCount;
        }

        if (backoff) {
            int increment = Main.SERVER_CONFIG.backoffIncrement.get();
            int maxDelay = Main.SERVER_CONFIG.backoffMaxDelay.get();
            for (Target target : targets) {
                if (target != null) {
                    target.applyBackoff(side, gameTime, increment, maxDelay);
                }
            }
        }

        tileEntity.setRoundRobinIndex(side, this, p);
        return itemsToTransfer < initialItems;
    }

    protected boolean insertOrdered(PipeLogicTileEntity tileEntity, Direction side, List<PipeTileEntity.Connection> connections, IItemHandler itemHandler) {
        int itemsToTransfer = getRate(tileEntity, side);
        int initialItems = itemsToTransfer;

        HolderLookup.Provider provider = tileEntity.getLevel().registryAccess();
        List<Filter<?, ?>> filters = tileEntity.getFilters(side, this);
        boolean blacklist = tileEntity.getFilterMode(side, this).equals(UpgradeTileEntity.FilterMode.BLACKLIST);
        int sourceSlots = itemHandler.getSlots();
        long gameTime = tileEntity.getLevel().getGameTime();
        boolean backoff = Main.SERVER_CONFIG.backoffEnabled.get();
        int increment = Main.SERVER_CONFIG.backoffIncrement.get();
        int maxDelay = Main.SERVER_CONFIG.backoffMaxDelay.get();

        ArrayList<ItemStack> nonFittingItems = new ArrayList<>();

        for (PipeTileEntity.Connection connection : connections) {
            if (itemsToTransfer <= 0) {
                break;
            }
            if (backoff && connection.isBackedOff(side, gameTime)) {
                continue;
            }
            IItemHandler destination = connection.getItemHandler();
            if (destination == null) {
                continue;
            }
            int destinationSlots = destination.getSlots();
            if (isFull(destination, destinationSlots)) {
                if (backoff) {
                    connection.onInsertFailed(side, gameTime, increment, maxDelay);
                }
                continue;
            }
            nonFittingItems.clear();
            boolean inserted = false;
            boolean refused = false;
            for (int i = 0; i < sourceSlots && itemsToTransfer > 0; i++) {
                ItemStack simulatedExtract = itemHandler.extractItem(i, itemsToTransfer, true);
                if (simulatedExtract.isEmpty()) {
                    continue;
                }
                if (containsStackable(nonFittingItems, simulatedExtract)) {
                    continue;
                }
                if (canInsert(provider, connection, simulatedExtract, filters) == blacklist) {
                    nonFittingItems.add(simulatedExtract);
                    continue;
                }
                ItemStack stack = insertItem(destination, destinationSlots, simulatedExtract);
                int insertedAmount = simulatedExtract.getCount() - stack.getCount();
                if (insertedAmount <= 0) {
                    nonFittingItems.add(simulatedExtract);
                    refused = true;
                    continue;
                }
                itemsToTransfer -= insertedAmount;
                itemHandler.extractItem(i, insertedAmount, false);
                inserted = true;
            }
            if (backoff) {
                if (inserted) {
                    connection.onInsertSuccess(side);
                } else if (refused) {
                    connection.onInsertFailed(side, gameTime, increment, maxDelay);
                }
            }
        }
        return itemsToTransfer < initialItems;
    }

    /**
     * Same as {@link net.neoforged.neoforge.items.ItemHandlerHelper#insertItem}, but doesn't query
     * {@link IItemHandler#getSlots()} on every iteration, which is expensive for some inventories (e.g. Mekanism).
     */
    private static ItemStack insertItem(IItemHandler destination, int slots, ItemStack stack) {
        for (int i = 0; i < slots; i++) {
            stack = destination.insertItem(i, stack, false);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        return stack;
    }

    private static boolean isFull(IItemHandler itemHandler, int slots) {
        for (int i = 0; i < slots; i++) {
            ItemStack stackInSlot = itemHandler.getStackInSlot(i);
            if (stackInSlot.getCount() < itemHandler.getSlotLimit(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Per operation state of a round robin destination.
     */
    private static final class Target {
        private final PipeTileEntity.Connection connection;
        @Nullable
        private final IItemHandler handler;
        private final int slots;
        @Nullable
        private ArrayList<ItemStack> rejected;
        private boolean done;
        private boolean inserted;
        private boolean refused;

        private Target(PipeTileEntity.Connection connection, Direction side, long gameTime, boolean backoff) {
            this.connection = connection;
            IItemHandler h = null;
            int s = 0;
            if (!backoff || !connection.isBackedOff(side, gameTime)) {
                h = connection.getItemHandler();
                if (h != null) {
                    s = h.getSlots();
                    // isFull is expensive on some inventories, so it is only checked once per operation.
                    // After that a failed insert marks the destination as done anyway.
                    if (isFull(h, s)) {
                        refused = true;
                        h = null;
                    }
                }
            }
            this.handler = h;
            this.slots = s;
        }

        private boolean hasRejected(ItemStack stack) {
            return rejected != null && containsStackable(rejected, stack);
        }

        private void reject(ItemStack stack) {
            if (rejected == null) {
                rejected = new ArrayList<>(4);
            }
            rejected.add(stack);
        }

        private void applyBackoff(Direction side, long gameTime, int increment, int maxDelay) {
            if (inserted) {
                connection.onInsertSuccess(side);
            } else if (refused) {
                connection.onInsertFailed(side, gameTime, increment, maxDelay);
            }
        }
    }

    private boolean canInsert(HolderLookup.Provider provider, PipeTileEntity.Connection connection, ItemStack stack, List<Filter<?, ?>> filters) {
        boolean hasNonInvertFilter = false;
        for (int i = 0; i < filters.size(); i++) {
            @SuppressWarnings("unchecked")
            Filter<?, Item> filter = (Filter<?, Item>) filters.get(i);
            if (!matchesConnection(connection, filter)) {
                continue;
            }
            if (filter.isInvert()) {
                if (matches(provider, filter, stack)) {
                    return false;
                }
            } else {
                hasNonInvertFilter = true;
                if (matches(provider, filter, stack)) {
                    return true;
                }
            }
        }
        return !hasNonInvertFilter;
    }

    private boolean matches(HolderLookup.Provider provider, Filter<?, Item> filter, ItemStack stack) {
        CompoundTag metadata = filter.getMetadata();
        if (metadata == null) {
            return filter.getTag() == null || filter.getTag().contains(stack.getItem());
        }
        CompoundTag stackNBT = ComponentUtils.getTag(provider, stack);
        if (filter.isExactMetadata()) {
            if (deepExactCompare(metadata, stackNBT)) {
                return filter.getTag() == null || filter.getTag().contains(stack.getItem());
            } else {
                return false;
            }
        } else {
            if (stackNBT.isEmpty()) {
                return metadata.size() <= 0;
            }
            if (!deepFuzzyCompare(metadata, stackNBT)) {
                return false;
            }
            return filter.getTag() == null || filter.getTag().contains(stack.getItem());
        }
    }

    private static boolean containsStackable(ArrayList<ItemStack> list, ItemStack stack) {
        for (int i = 0; i < list.size(); i++) {
            if (ItemUtils.isStackable(list.get(i), stack)) {
                return true;
            }
        }
        return false;
    }

    public int getSpeed(PipeLogicTileEntity tileEntity, Direction direction) {
        return getSpeed(tileEntity.getUpgrade(direction));
    }

    public int getSpeed(@Nullable Upgrade upgrade) {
        if (upgrade == null) {
            return Main.SERVER_CONFIG.itemPipeSpeed.get();
        }
        switch (upgrade) {
            case BASIC:
                return Main.SERVER_CONFIG.itemPipeSpeedBasic.get();
            case IMPROVED:
                return Main.SERVER_CONFIG.itemPipeSpeedImproved.get();
            case ADVANCED:
                return Main.SERVER_CONFIG.itemPipeSpeedAdvanced.get();
            case ULTIMATE:
                return Main.SERVER_CONFIG.itemPipeSpeedUltimate.get();
            case INFINITY:
            default:
                return 1;
        }
    }

    @Override
    public int getRate(@Nullable Upgrade upgrade) {
        if (upgrade == null) {
            return Main.SERVER_CONFIG.itemPipeAmount.get();
        }
        switch (upgrade) {
            case BASIC:
                return Main.SERVER_CONFIG.itemPipeAmountBasic.get();
            case IMPROVED:
                return Main.SERVER_CONFIG.itemPipeAmountImproved.get();
            case ADVANCED:
                return Main.SERVER_CONFIG.itemPipeAmountAdvanced.get();
            case ULTIMATE:
                return Main.SERVER_CONFIG.itemPipeAmountUltimate.get();
            case INFINITY:
            default:
                return Integer.MAX_VALUE;
        }
    }

    @Override
    public DataComponentType<ItemData> getDataComponentType() {
        return ModItems.ITEM_DATA_COMPONENT.get();
    }

    private static final ItemData DEFAULT = new ItemData(UpgradeTileEntity.FilterMode.WHITELIST, UpgradeTileEntity.RedstoneMode.IGNORED, UpgradeTileEntity.Distribution.NEAREST, Collections.emptyList());

    @Override
    public ItemData defaultData() {
        return DEFAULT;
    }

}
