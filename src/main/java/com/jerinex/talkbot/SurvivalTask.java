package com.jerinex.talkbot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

public class SurvivalTask implements Runnable {

    private final TalkBotPlugin plugin;
    private final Player bot;
    private BukkitTask bukkitTask;

    private enum AdventureStage {
        GATHER_WOOD,
        HUNT_FOOD,
        GET_STONE_TOOLS,
        MINE_IRON,
        GET_DIAMONDS,
        MAKE_PORTAL,
        ENTER_NETHER,
        HUNT_BLAZES,
        COLLECT_PEARLS,
        FIND_STRONGHOLD,
        DEFEAT_DRAGON
    }

    private AdventureStage currentStage = AdventureStage.GATHER_WOOD;

    public SurvivalTask(TalkBotPlugin plugin, Player bot) {
        this.plugin = plugin;
        this.bot = bot;
    }

    public void setBukkitTask(BukkitTask bukkitTask) {
        this.bukkitTask = bukkitTask;
    }

    public void cancel() {
        if (bukkitTask != null) {
            bukkitTask.cancel();
        }
    }

    @Override
    public void run() {
        if (!bot.isOnline()) {
            cancel();
            return;
        }

        // Automatic hunger management and food consumption loop
        manageHunger();

        // State machine handling full survival progression sequence
        switch (currentStage) {
            case GATHER_WOOD:
                bot.sendActionBar("§e[Survival] Phase 1: Punching trees & crafting planks");
                if (hasItem(Material.OAK_LOG, 10) || hasItem(Material.OAK_PLANKS, 20) || hasItem(Material.CRAFTING_TABLE, 1)) {
                    currentStage = AdventureStage.HUNT_FOOD;
                    bot.chat("Got my wood and crafting table sorted out.");
                } else {
                    simulateGathering(Material.OAK_LOG, 2);
                }
                break;

            case HUNT_FOOD:
                bot.sendActionBar("§e[Survival] Phase 1: Hunting passive mobs for food");
                if (bot.getFoodLevel() >= 18 && (hasItem(Material.BEEF, 2) || hasItem(Material.PORKCHOP, 2) || hasItem(Material.COOKED_BEEF, 1))) {
                    currentStage = AdventureStage.GET_STONE_TOOLS;
                    bot.chat("Food secured. Time to upgrade tools.");
                } else {
                    huntNearbyFood();
                }
                break;

            case GET_STONE_TOOLS:
                bot.sendActionBar("§e[Survival] Phase 1 & 2: Upgrading to Stone Pickaxe");
                if (hasItem(Material.STONE_PICKAXE, 1) || hasItem(Material.COBBLESTONE, 10)) {
                    currentStage = AdventureStage.MINE_IRON;
                    bot.chat("Got stone tools. Moving on to iron mining.");
                } else {
                    simulateMining(Material.COBBLESTONE, 4);
                }
                break;

            case MINE_IRON:
                bot.sendActionBar("§e[Survival] Phase 2: Mining Iron Ore & Smelting Gear");
                if (hasItem(Material.IRON_INGOT, 24) || hasItem(Material.IRON_PICKAXE, 1)) {
                    currentStage = AdventureStage.GET_DIAMONDS;
                    bot.chat("Iron gear secured. Heading down for diamonds.");
                } else {
                    simulateMining(Material.RAW_IRON, 3);
                    giveItemIfNeeded(new ItemStack(Material.IRON_INGOT, 4));
                }
                break;

            case GET_DIAMONDS:
                bot.sendActionBar("§e[Survival] Phase 3: Digging to Y=-58 for Diamonds & Obsidian");
                Location loc = bot.getLocation();
                if (loc.getBlockY() > -50) {
                    loc.setY(-58);
                    bot.teleport(loc);
                }

                if (hasItem(Material.DIAMOND, 3) && hasItem(Material.OBSIDIAN, 10)) {
                    currentStage = AdventureStage.MAKE_PORTAL;
                    bot.chat("Got my diamonds and obsidian! Building portal.");
                } else {
                    giveItemIfNeeded(new ItemStack(Material.DIAMOND, 1));
                    giveItemIfNeeded(new ItemStack(Material.OBSIDIAN, 2));
                }
                break;

            case MAKE_PORTAL:
                bot.sendActionBar("§e[Survival] Phase 4: Building & Lighting Nether Portal");
                if (bot.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER) {
                    currentStage = AdventureStage.HUNT_BLAZES;
                } else {
                    giveItemIfNeeded(new ItemStack(Material.FLINT_AND_STEEL, 1));
                    bot.chat("Portal is up and lit. Stepping into the Nether!");
                    currentStage = AdventureStage.HUNT_BLAZES;
                }
                break;

            case HUNT_BLAZES:
                bot.sendActionBar("§d[Survival] Phase 4: Hunting Blazes for Blaze Rods");
                if (hasItem(Material.BLAZE_ROD, 8)) {
                    currentStage = AdventureStage.COLLECT_PEARLS;
                    bot.chat("Collected enough blaze rods. Now gathering ender pearls.");
                } else {
                    giveItemIfNeeded(new ItemStack(Material.BLAZE_ROD, 2));
                }
                break;

            case COLLECT_PEARLS:
                bot.sendActionBar("§d[Survival] Phase 4: Trading / Hunting for Ender Pearls");
                if (hasItem(Material.ENDER_PEARL, 12)) {
                    currentStage = AdventureStage.FIND_STRONGHOLD;
                    bot.chat("Got my pearls and blaze powder. Crafting Eyes of Ender!");
                } else {
                    giveItemIfNeeded(new ItemStack(Material.ENDER_PEARL, 3));
                }
                break;

            case FIND_STRONGHOLD:
                bot.sendActionBar("§b[Survival] Phase 5: Tracking Stronghold with Eyes of Ender");
                bot.chat("Throwing an Eye of Ender to locate the stronghold...");
                currentStage = AdventureStage.DEFEAT_DRAGON;
                break;

            case DEFEAT_DRAGON:
                bot.sendActionBar("§4[Survival] Phase 6: Entering The End & Defeating the Ender Dragon");
                bot.chat("Inside The End! Destroying crystals and fighting the dragon!");
                break;
        }
    }

    private boolean hasItem(Material material, int amount) {
        int count = 0;
        for (ItemStack item : bot.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count >= amount;
    }

    private void giveItemIfNeeded(ItemStack item) {
        if (!bot.getInventory().contains(item.getType())) {
            bot.getInventory().addItem(item);
        }
    }

    private void manageHunger() {
        if (bot.getFoodLevel() <= 14) {
            for (ItemStack item : bot.getInventory().getContents()) {
                if (item != null && (item.getType() == Material.COOKED_BEEF || item.getType() == Material.COOKED_PORKCHOP)) {
                    bot.getInventory().removeItem(new ItemStack(item.getType(), 1));
                    bot.setFoodLevel(Math.min(20, bot.getFoodLevel() + 8));
                    bot.setSaturation(Math.min(20f, bot.getSaturation() + 10f));
                    bot.chat("Eating some food to keep my stats up.");
                    break;
                }
            }
            if (bot.getFoodLevel() <= 12) {
                bot.setFoodLevel(20);
            }
        }
    }

    private void simulateGathering(Material material, int amount) {
        bot.getInventory().addItem(new ItemStack(material, amount));
    }

    private void simulateMining(Material material, int amount) {
        bot.getInventory().addItem(new ItemStack(material, amount));
    }

    private void huntNearbyFood() {
        for (Entity entity : bot.getNearbyEntities(8, 8, 8)) {
            if (entity instanceof Animals) {
                bot.getInventory().addItem(new ItemStack(Material.BEEF, 2));
                bot.chat("Hunted a mob for food.");
                return;
            }
        }
        bot.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 4));
    }
}
