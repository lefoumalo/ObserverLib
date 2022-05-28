package hellfirepvp.observerlib.common.data;

import com.google.common.collect.Maps;
import hellfirepvp.observerlib.ObserverLib;
import hellfirepvp.observerlib.api.ChangeObserver;
import hellfirepvp.observerlib.api.ChangeSubscriber;
import hellfirepvp.observerlib.api.ObservableArea;
import hellfirepvp.observerlib.api.ObserverProvider;
import hellfirepvp.observerlib.common.change.MatchChangeSubscriber;
import hellfirepvp.observerlib.common.data.base.SectionWorldData;
import hellfirepvp.observerlib.common.data.base.WorldSection;
import hellfirepvp.observerlib.common.registry.RegistryProviders;
import hellfirepvp.observerlib.common.util.NBTHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 * This class is part of the ObserverLib Mod
 * The complete source code for this mod can be found on github.
 * Class: StructureMatchingBuffer
 * Created by HellFirePvP
 * Date: 25.04.2019 / 20:48
 */
public class StructureMatchingBuffer extends SectionWorldData<StructureMatchingBuffer.MatcherSectionData> {

    public StructureMatchingBuffer(WorldCacheDomain.SaveKey<? extends StructureMatchingBuffer> key) {
        super(key, PRECISION_CHUNK);
    }

    @Override
    public MatcherSectionData createNewSection(int sectionX, int sectionZ) {
        return new MatcherSectionData(sectionX, sectionZ);
    }

    @Override
    public void updateTick(Level world) {}

    @Nonnull
    public <T extends ChangeObserver> MatchChangeSubscriber<T> observeArea(Level world, BlockPos center, ObserverProvider provider) {
        MatchChangeSubscriber<T> existing;
        if ((existing = (MatchChangeSubscriber<T>) getSubscriber(center)) != null) {
            if (!existing.getObserver().getProviderRegistryName().equals(provider.getRegistryName())) {
                ObserverLib.log.warn("Trying to observe area at dim=" + world.dimension().location() + " " + center.toString() +
                        " while it is already being observed by " + existing.getObserver().getProviderRegistryName());
                ObserverLib.log.warn("Removing existing observer!");
                this.write(() -> this.removeSubscriber(center));
            } else {
                return existing;
            }
        }

        T observer = (T) provider.provideObserver();
        MatchChangeSubscriber<T> subscriber = new MatchChangeSubscriber<>(center, observer);

        for (ChunkPos chPos : subscriber.getObservableChunks()) {
            MatcherSectionData data = getOrCreateSection(chPos.getWorldPosition());
            this.write(() -> data.addSubscriber(center, subscriber));
            markDirty(data);
        }
        observer.initialize(world, center);
        return subscriber;
    }

    public boolean removeSubscriber(BlockPos pos) {
        MatcherSectionData data = getOrCreateSection(pos);

        ChangeSubscriber<? extends ChangeObserver> removed = this.write(() -> data.removeSubscriber(pos));
        if (removed != null) {
            ObservableArea area = removed.getObserver().getObservableArea();
            for (ChunkPos chPos : area.getAffectedChunks(pos)) {
                MatcherSectionData matchData = getOrCreateSection(chPos.getWorldPosition());
                this.write(() -> matchData.removeSubscriber(pos));
                markDirty(matchData);
            }
        }
        return removed != null;
    }

    @Nullable
    public ChangeSubscriber<? extends ChangeObserver> getSubscriber(BlockPos pos) {
        return this.write(() -> getOrCreateSection(pos).getSubscriber(pos));
    }

    @Nonnull
    public Collection<MatchChangeSubscriber<?>> getSubscribers(ChunkPos pos) {
        MatcherSectionData data = getOrCreateSection(pos.getWorldPosition());
        return this.read(() -> new ArrayList<>(data.requestSubscribers.values()));
    }

    @Override
    public void writeToNBT(CompoundTag nbt) {}

    @Override
    public void readFromNBT(CompoundTag nbt) {}

    public static class MatcherSectionData extends WorldSection {

        private final Map<BlockPos, MatchChangeSubscriber<? extends ChangeObserver>> requestSubscribers = Maps.newHashMap();

        private MatcherSectionData(int sX, int sZ) {
            super(sX, sZ);
        }

        @Nullable
        private MatchChangeSubscriber<? extends ChangeObserver> getSubscriber(BlockPos pos) {
            return this.requestSubscribers.get(pos);
        }

        @Nullable
        private ChangeSubscriber<? extends ChangeObserver> removeSubscriber(BlockPos pos) {
            return this.requestSubscribers.remove(pos);
        }

        @Nullable
        private ChangeSubscriber<? extends ChangeObserver> addSubscriber(BlockPos pos, MatchChangeSubscriber<? extends ChangeObserver> subscriber) {
            return this.requestSubscribers.put(pos, subscriber);
        }

        @Override
        public void writeToNBT(CompoundTag tag) {
            ListTag subscriberList = new ListTag();

            for (MatchChangeSubscriber<? extends ChangeObserver> sub : this.requestSubscribers.values()) {
                CompoundTag subscriber = new CompoundTag();
                NBTHelper.writeBlockPosToNBT(sub.getCenter(), subscriber);
                subscriber.putString("identifier", sub.getObserver().getProviderRegistryName().toString());

                NBTHelper.setAsSubTag(subscriber, "matchData", sub::writeToNBT);

                subscriberList.add(subscriber);
            }

            tag.put("subscribers", subscriberList);
        }

        @Override
        public void readFromNBT(CompoundTag tag) {
            this.requestSubscribers.clear();

            ListTag subscriberList = tag.getList("subscribers", Tag.TAG_COMPOUND);
            for (int i = 0; i < subscriberList.size(); i++) {
                CompoundTag subscriberTag = subscriberList.getCompound(i);

                BlockPos requester = NBTHelper.readBlockPosFromNBT(subscriberTag);
                ResourceLocation matchIdentifier = new ResourceLocation(subscriberTag.getString("identifier"));
                ObserverProvider observer = RegistryProviders.getProvider(matchIdentifier);
                if (observer == null) {
                    ObserverLib.log.warn("Unknown Observer Provider: " + matchIdentifier.toString() + "! Skipping...");
                    continue;
                }

                MatchChangeSubscriber<?> subscriber = new MatchChangeSubscriber<>(requester, observer.provideObserver());
                subscriber.readFromNBT(subscriberTag.getCompound("matchData"));

                this.requestSubscribers.put(subscriber.getCenter(), subscriber);
            }
        }
    }

}
