package com.forgeward.travelcam;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.tags.FluidTags;

/**
 * The land the director scores: one column at a time, from loaded chunks only, cached. Nothing here samples noise or biomes (about 1 ms a
 * noise-biome sample, memory noise-biome-sample-cost): the height comes from the chunk's MOTION_BLOCKING heightmap walked down through trees, and the top block says water or
 * built. A column not loaded on this client is UNKNOWN and scores nothing.
 *
 * A cached column is kept 400 ticks (land changes slowly; a new build shows up within 20 s). Packed in a long:
 * stamp (ticks, 32 bits) | height + 4096 (16 bits) | flags (8 bits).
 */
final class Land {
    private Land() { }

    static final int UNKNOWN = Integer.MIN_VALUE;
    static final int WATER = 1, BUILT = 2, TREE = 4;

    private static final int KEEP = 400, MOST = 32768;
    private static final Long2LongOpenHashMap CACHE = new Long2LongOpenHashMap();
    private static ClientLevel cachedFor;
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    static long samples, hits, unloaded, deferred;

    private static long now;
    /** New columns one read may sample (1a: the first read of an empty cache took 15 ms). About 3.6 us each. */
    static final int PER_READ = 256;
    private static int budget;
    static void tick(long gameTime) { now = gameTime; budget = PER_READ; }
    /** More new columns for what follows a read (1c: the read spent the budget, so the open-spot search saw every column as unknown: 0 of 219). */
    static void grant(int n) { budget += n; }

    /** The packed column, or 0 when unknown. */
    private static long column(ClientLevel level, int x, int z) {
        if (level != cachedFor) { CACHE.clear(); cachedFor = level; }
        long key = BlockPos.asLong(x, 0, z);
        long v = CACHE.get(key);
        if (v != 0 && now - (v >>> 32) < KEEP) { hits++; return v; }
        if (!level.hasChunk(x >> 4, z >> 4)) { unloaded++; return 0; }
        if (budget <= 0) { deferred++; return 0; }   // the rest of this read waits for the next one: no one read fills the cache
        budget--;
        samples++;
        // MOTION_BLOCKING is the heightmap a client has that sees solid ground. MOTION_BLOCKING_NO_LEAVES is LIVE_WORLD only, never sent
        // to clients (Heightmap$Types, javap): course 1 and 2a read it, got the bottom of the world everywhere, and scored blank land.
        // So walk down from the top through a tree to the ground.
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), floor = Math.max(level.getMinBuildHeight(), h - WALK);
        boolean tree = false;
        BlockState top = level.getBlockState(POS.set(x, h - 1, z));
        while (h > floor) {
            if (isTree(top)) tree = true;
            // a fluid is the surface, though water and lava are in minecraft:replaceable (2c: the walk went through rivers, water 0% on every run)
            else if (!top.getFluidState().isEmpty() || !top.isAir() && !top.is(BlockTags.REPLACEABLE)) break;
            h--;
            top = level.getBlockState(POS.set(x, h - 1, z));
        }
        int flags = tree ? TREE : 0;
        if (top.getFluidState().is(FluidTags.WATER)) flags |= WATER;   // the tag: Streams' river fluids are in it too
        else if (built(top)) flags |= BUILT;
        lowest = Math.min(lowest, h); highest = Math.max(highest, h);
        if ((flags & WATER) != 0) wetSeen++;
        if (tree) treesSeen++;
        if (CACHE.size() >= MOST) CACHE.clear();
        v = (now << 32) | ((long) ((h + 4096) & 0xFFFF) << 8) | flags | 0x80;   // 0x80: never 0 for a known column
        CACHE.put(key, v);
        return v;
    }

    /** Ground height, or UNKNOWN. */
    static int height(ClientLevel level, int x, int z) {
        long v = column(level, x, z);
        return v == 0 ? UNKNOWN : (int) ((v >>> 8) & 0xFFFF) - 4096;
    }

    /** WATER / BUILT flags, or -1 when unknown. */
    static int flags(ClientLevel level, int x, int z) {
        long v = column(level, x, z);
        return v == 0 ? -1 : (int) (v & 0x7F);
    }

    /** A top block nature doesn't put there: planks, bricks, glass, wool and the like. Read by tags that are natural, so a modded stone
     *  still counts as land. Only what's on top counts: a path in the grass is land, a roof is built. */
    private static boolean built(BlockState s) {
        if (s.isAir()) return false;
        if (s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)
                || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE) || s.is(BlockTags.TERRACOTTA) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.REPLACEABLE)
                || s.is(BlockTags.STONE_ORE_REPLACEABLES) || s.is(BlockTags.MOSS_REPLACEABLE) || s.is(BlockTags.NYLIUM)) return false;
        return s.is(BlockTags.PLANKS) || s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.WALLS) || s.is(BlockTags.FENCES)
                || s.is(BlockTags.WOOL) || s.is(BlockTags.IMPERMEABLE) || s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS)
                || s.getBlock().getDescriptionId().contains("brick") || s.getBlock().getDescriptionId().contains("tile");
    }

    static int cached() { return CACHE.size(); }
    static void resetCounts() { samples = hits = unloaded = deferred = wetSeen = treesSeen = 0; lowest = Integer.MAX_VALUE; highest = Integer.MIN_VALUE; }

    private static final int WALK = 48;   // deeper than any tree
    static long wetSeen, treesSeen;
    private static int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;

    /** Leaves, a trunk, or a Dynamic Trees branch (by name: no compile link to that mod). */
    private static boolean isTree(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                || s.getBlock().getDescriptionId().contains("branch");
    }

    /** What the columns read this use looked like: a blank read shows up here as one height and nothing else. */
    static String seen() {
        if (samples == 0) return "none";
        return String.format(java.util.Locale.ROOT, "ground %d..%d, water %.0f%%, trees %.0f%%", lowest, highest, wetSeen * 100.0 / samples, treesSeen * 100.0 / samples);
    }
}
