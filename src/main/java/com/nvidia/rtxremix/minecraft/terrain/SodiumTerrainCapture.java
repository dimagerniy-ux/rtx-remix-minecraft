/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Community NeoForge port contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBlockData;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Experimental bounded terrain capture for Sodium-based NeoForge clients.
 *
 * Sodium builds its chunk geometry in ChunkBuilderMeshingTask and bypasses
 * SectionCompiler. Our vanilla SectionBuilderMixin therefore never runs and
 * all RTX terrain counters remain zero. This fallback samples loaded sections
 * on the render thread and publishes the same SectionBlockData that the
 * vanilla compiler mixin does, without requiring Sodium classes at link time.
 *
 * It is intentionally paced: complete Sodium integration should eventually
 * capture its immutable LevelSlice during a chunk rebuild, rather than scan a
 * second copy of each section.
 */
public final class SodiumTerrainCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger(SodiumTerrainCapture.class);
    private static final int RADIUS = Math.max(2, Math.min(16,
            Integer.getInteger("rtxremix.sodiumCapture.radiusChunks", 8)));
    private static final int MAX_SECTIONS_PER_FRAME = Math.max(1, Math.min(8,
            Integer.getInteger("rtxremix.sodiumCapture.sectionsPerFrame", 2)));
    private static final long BUDGET_NS = Math.max(250_000L,
            Long.getLong("rtxremix.sodiumCapture.frameBudgetNs", 3_000_000L));
    private static final long RESCAN_AFTER_FRAMES = Math.max(120L,
            Long.getLong("rtxremix.sodiumCapture.rescanFrames", 3600L));
    private static final long REPLAN_AFTER_FRAMES = 30L;

    private ClientLevel world;
    private long frame;
    private long lastPlannedFrame = -REPLAN_AFTER_FRAMES;
    private int lastCenterX = Integer.MIN_VALUE;
    private int lastCenterZ = Integer.MIN_VALUE;
    private final ArrayDeque<SectionPos> pending = new ArrayDeque<>();
    /** Client block updates must not wait for the periodic whole-section rescan. */
    private final ArrayDeque<SectionPos> dirty = new ArrayDeque<>();
    private final Set<Long> dirtyKeys = new HashSet<>();
    private final Map<Long, Long> capturedAtFrame = new HashMap<>();
    private final BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
    private long completedCaptures;
    private long lastProgressLogFrame;

    public void update(ClientLevel currentWorld, Vec3 camera, BlockTextureManager textures) {
        if (!RtxRemixMod.isWorldActive() || Boolean.getBoolean("rtxremix.sodiumCapture.disabled")) {
            return;
        }
        if (currentWorld != world) {
            clear();
            world = currentWorld;
        }
        frame++;
        int cx = ((int) Math.floor(camera.x)) >> 4;
        int cz = ((int) Math.floor(camera.z)) >> 4;
        if (cx != lastCenterX || cz != lastCenterZ
                || frame - lastPlannedFrame >= REPLAN_AFTER_FRAMES) {
            plan(cx, cz, (int) Math.floor(camera.y) >> 4);
        }

        long deadline = System.nanoTime() + BUDGET_NS;
        int attempts = 0;
        while (attempts++ < MAX_SECTIONS_PER_FRAME && (!dirty.isEmpty() || !pending.isEmpty())) {
            if (attempts > 1 && System.nanoTime() >= deadline) break;
            boolean urgent = !dirty.isEmpty();
            SectionPos section = urgent ? dirty.removeFirst() : pending.removeFirst();
            long sectionKey = section.asLong();
            if (urgent) dirtyKeys.remove(sectionKey);
            Long lastCapture = capturedAtFrame.get(sectionKey);
            if (lastCapture != null && frame - lastCapture < RESCAN_AFTER_FRAMES) continue;
            LevelChunk chunk = currentWorld.getChunkSource().getChunk(section.getX(), section.getZ(), false);
            if (chunk == null) continue;
            int localSection = section.getY() - currentWorld.getMinSection();
            if (localSection < 0 || localSection >= chunk.getSections().length) continue;
            LevelChunkSection chunkSection = chunk.getSections()[localSection];
            if (chunkSection == null || (chunkSection.hasOnlyAir() && !urgent)) continue;
            // An urgent all-air capture is necessary to retire geometry after the last block is broken.
            if (captureSection(section, currentWorld, textures)) {
                capturedAtFrame.put(sectionKey, frame);
                completedCaptures++;
            }
        }
        if (completedCaptures > 0 && frame - lastProgressLogFrame >= 240) {
            lastProgressLogFrame = frame;
            LOGGER.info("Sodium terrain fallback: {} sections captured, {} queued, {} cached",
                    completedCaptures, pending.size(), capturedAtFrame.size());
        }
    }

    private void plan(int cx, int cz, int cameraSectionY) {
        lastCenterX = cx;
        lastCenterZ = cz;
        lastPlannedFrame = frame;
        pending.clear();
        Set<Long> alreadyQueued = new HashSet<>();
        List<SectionPos> candidates = new ArrayList<>();
        int radius = Math.min(RADIUS, Minecraft.getInstance().options.renderDistance().get());
        int minSy = world.getMinSection();
        int maxSy = minSy + world.getSectionsCount();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = cx + dx, z = cz + dz;
                LevelChunk chunk = world.getChunkSource().getChunk(x, z, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = minSy; sy < maxSy; sy++) {
                    int index = sy - minSy;
                    if (index < 0 || index >= sections.length
                            || sections[index] == null || sections[index].hasOnlyAir()) continue;
                    SectionPos section = SectionPos.of(x, sy, z);
                    long key = section.asLong();
                    Long lastCapture = capturedAtFrame.get(key);
                    if (lastCapture != null && frame - lastCapture < RESCAN_AFTER_FRAMES) continue;
                    if (alreadyQueued.add(key)) candidates.add(section);
                }
            }
        }
        candidates.sort(Comparator.comparingLong(pos -> {
            long dx = pos.getX() - cx;
            long dz = pos.getZ() - cz;
            long dy = pos.getY() - cameraSectionY;
            return 16L * (dx * dx + dz * dz) + dy * dy;
        }));
        pending.addAll(candidates);
    }

    private boolean captureSection(SectionPos section, ClientLevel clientWorld,
                                   BlockTextureManager textures) {
        BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
        BlockDataQueue.CaptureToken token = BlockDataQueue.beginCapture(section.getX(), section.getZ());
        try {
            emitter.beginSection(section, textures, Minecraft.getInstance().getBlockRenderer());
            for (int y = section.minBlockY(); y < section.minBlockY() + 16; y++) {
                for (int z = section.minBlockZ(); z < section.minBlockZ() + 16; z++) {
                    for (int x = section.minBlockX(); x < section.minBlockX() + 16; x++) {
                        blockPos.set(x, y, z);
                        BlockState state = clientWorld.getBlockState(blockPos);
                        emitter.processBlock(state, clientWorld, blockPos,
                                state.isSolidRender(clientWorld, blockPos));
                        if (BlockQuadEmitter.getRenderTypeCached(state) == RenderShape.MODEL) {
                            emitter.emitBlock(state, blockPos, clientWorld);
                        }
                        FluidState fluid = state.getFluidState();
                        if (!fluid.isEmpty()) {
                            emitter.emitFluid(blockPos, clientWorld, fluid);
                        }
                    }
                }
            }
            SectionBlockData capture = emitter.finishSection(section);
            BlockDataQueue.enqueue(capture, token);
            return true;
        } catch (RuntimeException exception) {
            emitter.abortSection();
            LOGGER.error("Sodium terrain fallback failed to capture section ({}, {}, {})",
                    section.getX(), section.getY(), section.getZ(), exception);
            return false;
        }
    }

    /** Called from LevelRenderer.setSectionDirty when a block/section changes. */
    public void invalidateSection(int cx, int sy, int cz) {
        if (world == null) return;
        long key = SectionPos.asLong(cx, sy, cz);
        capturedAtFrame.remove(key);
        if (dirtyKeys.add(key)) {
            dirty.addLast(SectionPos.of(cx, sy, cz));
        }
    }

    public void forgetColumn(int cx, int cz) {
        dirty.removeIf(section -> section.getX() == cx && section.getZ() == cz);
        dirtyKeys.removeIf(key -> {
            SectionPos section = SectionPos.of(key);
            return section.getX() == cx && section.getZ() == cz;
        });
        capturedAtFrame.keySet().removeIf(key -> {
            SectionPos section = SectionPos.of(key);
            return section.getX() == cx && section.getZ() == cz;
        });
        pending.removeIf(section -> section.getX() == cx && section.getZ() == cz);
    }

    public void clear() {
        world = null;
        frame = 0;
        lastPlannedFrame = -REPLAN_AFTER_FRAMES;
        lastCenterX = Integer.MIN_VALUE;
        lastCenterZ = Integer.MIN_VALUE;
        pending.clear();
        dirty.clear();
        dirtyKeys.clear();
        capturedAtFrame.clear();
        completedCaptures = 0;
        lastProgressLogFrame = 0;
    }
}
