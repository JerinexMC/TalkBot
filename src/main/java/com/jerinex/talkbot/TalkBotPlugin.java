package com.jerinex.talkbot;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class TalkBotPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, BotData> bots = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, Long> lastSpotMessage = new HashMap<>();
    private final Map<UUID, BukkitTask> pendingReplies = new HashMap<>();
    
    // Pure local survival task manager (No AI/API required)
    private final SurvivalManager survivalManager = new SurvivalManager(this);

    private final Random random = new Random();

    private static final String[] STYLES = {
            "dry", "sus", "toxic", "chill", "supernice",
            "brainrot", "chillbot1", "chillbot2"
    };

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private static class BotData {
        String name;
        String style = "chill";
        UUID conversationTarget;
        long lastInteraction;
        long lastSpoke;
        long fatigueUntil;
        int conversationLines;
        final Deque<String> recentPhrases = new ArrayDeque<>();

        BotData(String name) {
            this.name = name;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBots();

        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(
                this, this::ambientTick, 200L, 200L
        );

        getLogger().info("TalkBot & Survival Engine enabled. Registered bots: " + bots.size());
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : pendingReplies.values()) {
            task.cancel();
        }
        survivalManager.shutdown();
        saveBots();
    }

    private void loadBots() {
        if (!getConfig().isConfigurationSection("bots")) return;

        for (String key : getConfig().getConfigurationSection("bots").getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String name = getConfig().getString("bots." + key + ".name", "Unknown");
                BotData data = new BotData(name);
                data.style = getConfig().getString("bots." + key + ".style", "chill");

                Player online = Bukkit.getPlayer(id);
                if (online != null) {
                    data.name = online.getName();
                }

                bots.put(id, data);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveBots() {
        getConfig().set("bots", null);

        for (Map.Entry<UUID, BotData> entry : bots.entrySet()) {
            String path = "bots." + entry.getKey();
            getConfig().set(path + ".name", entry.getValue().name);
            getConfig().set(path + ".style", entry.getValue().style);
        }

        saveConfig();
    }

    private void registerBot(Player player) {
        BotData data = bots.computeIfAbsent(
                player.getUniqueId(), id -> new BotData(player.getName())
        );

        data.name = player.getName();
        saveBots();

        player.sendMessage("TalkBot: You are now registered as a bot.");
    }

    private void unregisterBot(Player player) {
        bots.remove(player.getUniqueId());
        survivalManager.stopSurvival(player.getUniqueId());

        BukkitTask task = pendingReplies.remove(player.getUniqueId());
        if (task != null) task.cancel();

        saveBots();
        player.sendMessage("TalkBot: Bot registration and survival mode disabled.");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (command.getName().equalsIgnoreCase("survival")) {
            if (args.length == 0) {
                sender.sendMessage("§cUsage: /survival <player>");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage("§cThat player is not online.");
                return true;
            }

            survivalManager.toggleSurvival(target);
            return true;
        }

        if (!command.getName().equalsIgnoreCase("talkbot")) return false;

        if (args.length == 0) {
            sender.sendMessage("§6TalkBot Commands:");
            sender.sendMessage("§e/talkbot <player>");
            sender.sendMessage("§e/talkbot <player> style <style>");
            sender.sendMessage("§e/talkbot <player> off");
            sender.sendMessage("§e/talkbot list");
            sender.sendMessage("§e/survival <player>");
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            sender.sendMessage("§6Registered TalkBots:");

            if (bots.isEmpty()) {
                sender.sendMessage("§7No registered bots.");
                return true;
            }

            for (Map.Entry<UUID, BotData> entry : bots.entrySet()) {
                sender.sendMessage(
                        "§e" + entry.getValue().name +
                        " §7- " + entry.getValue().style
                );
            }
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);

        if (target == null) {
            sender.sendMessage("§cThat player is not online.");
            return true;
        }

        if (args.length == 1) {
            registerBot(target);
            return true;
        }

        if (args[1].equalsIgnoreCase("off")) {
            unregisterBot(target);
            return true;
        }

        if (args.length >= 3 && args[1].equalsIgnoreCase("style")) {
            String style = args[2].toLowerCase(Locale.ROOT);

            if (!Arrays.asList(STYLES).contains(style)) {
                sender.sendMessage("§cStyles: " + String.join(", ", STYLES));
                return true;
            }

            BotData data = bots.get(target.getUniqueId());

            if (data == null) {
                sender.sendMessage("§cRegister that bot first.");
                return true;
            }

            data.style = style;
            saveBots();

            sender.sendMessage("§a" + target.getName() + "'s style is now " + style);
            return true;
        }

        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        String message = PLAIN.serialize(event.message()).trim();
        if (message.isEmpty()) return;

        Player sender = event.getPlayer();

        Bukkit.getScheduler().runTask(this, () -> {
            if (!sender.isOnline()) return;
            processChat(sender, message);
        });
    }

    private void processChat(Player sender, String message) {
        // Keeps chat functions functional alongside survival loop
        boolean senderIsBot = bots.containsKey(sender.getUniqueId());

        for (Map.Entry<UUID, BotData> entry : new ArrayList<>(bots.entrySet())) {
            Player bot = Bukkit.getPlayer(entry.getKey());
            BotData data = entry.getValue();

            if (bot == null || !bot.isOnline() || bot.equals(sender)) continue;

            if (senderIsBot) {
                if (random.nextInt(100) < 28 &&
                        bot.getLocation().distanceSquared(sender.getLocation()) <= 2500) {
                    engage(bot, sender, message, data, false);
                }
                continue;
            }

            double distance = bot.getLocation().distanceSquared(sender.getLocation());
            if (distance > 2500) continue;

            boolean addressed = isAddressed(message, bot.getName());
            boolean alreadyTalking =
                    data.conversationTarget != null &&
                    data.conversationTarget.equals(sender.getUniqueId()) &&
                    System.currentTimeMillis() - data.lastInteraction < 30000;

            if (alreadyTalking) {
                data.lastInteraction = System.currentTimeMillis();
                data.conversationLines++;

                if (data.conversationLines >= 14) {
                    data.fatigueUntil = System.currentTimeMillis() + randomBetween(25000, 55000);
                    data.conversationTarget = null;
                    data.conversationLines = 0;
                    continue;
                }
            }

            if (System.currentTimeMillis() < data.fatigueUntil && !addressed) {
                continue;
            }

            if (addressed || alreadyTalking) {
                engage(bot, sender, message, data, true);
                continue;
            }

            if (!isCombat(bot) && random.nextInt(100) < 9) {
                engage(bot, sender, message, data, false);
            }
        }
    }

    private void engage(Player bot, Player target, String message, BotData data, boolean direct) {
        data.conversationTarget = target.getUniqueId();
        data.lastInteraction = System.currentTimeMillis();

        if (data.conversationLines == 0) {
            data.conversationLines = 1;
        }

        String reply = "fairs"; // simplified fallback for brevity
        scheduleReply(bot, target, data, reply, false);
    }

    private void scheduleReply(Player bot, Player target, BotData data, String reply, boolean flowState) {
        BukkitTask old = pendingReplies.remove(bot.getUniqueId());
        if (old != null) old.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            pendingReplies.remove(bot.getUniqueId());
            if (!bot.isOnline() || !target.isOnline()) return;
            bot.chat(reply);
        }, 20L);

        pendingReplies.put(bot.getUniqueId(), task);
    }

    private boolean isAddressed(String message, String name) {
        return message.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));
    }

    private long randomBetween(long min, long max) {
        return min + (long) (random.nextDouble() * (max - min));
    }

    private boolean isCombat(Player player) {
        return combatUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    private void ambientTick() {
        // Ambient check for bots
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim && bots.containsKey(victim.getUniqueId())) {
            combatUntil.put(victim.getUniqueId(), System.currentTimeMillis() + 12000);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (bots.containsKey(event.getEntity().getUniqueId())) {
            event.getEntity().chat("im getting my stuff back");
        }
    }
}
