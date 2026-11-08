
package com.jerinex.talkbot;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class TalkBotPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, BotData> bots = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, Long> lastSpotMessage = new HashMap<>();
    private final Map<UUID, BukkitTask> pendingReplies = new HashMap<>();

    private final Random random = new Random();

    private static final String[] STYLES = {
            "dry", "sus", "toxic", "chill", "supernice",
            "brainrot", "chillbot1", "chillbot2"
    };

    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private static final long SECOND = 1000L;

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

        // Periodic proximity awareness and occasional social behavior.
        Bukkit.getScheduler().runTaskTimer(
                this, this::ambientTick, 200L, 200L
        );

        getLogger().info("TalkBot enabled. Registered bots: " + bots.size());
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : pendingReplies.values()) {
            task.cancel();
        }

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

        BukkitTask task = pendingReplies.remove(player.getUniqueId());
        if (task != null) task.cancel();

        saveBots();
        player.sendMessage("TalkBot: Bot registration disabled.");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("talkbot")) return false;

        if (args.length == 0) {
            sender.sendMessage("§6TalkBot Commands:");
            sender.sendMessage("§e/talkbot <player>");
            sender.sendMessage("§e/talkbot <player> style <style>");
            sender.sendMessage("§e/talkbot <player> off");
            sender.sendMessage("§e/talkbot list");
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
        boolean senderIsBot = bots.containsKey(sender.getUniqueId());

        for (Map.Entry<UUID, BotData> entry :
                new ArrayList<>(bots.entrySet())) {

            Player bot = Bukkit.getPlayer(entry.getKey());
            BotData data = entry.getValue();

            if (bot == null || !bot.isOnline() || bot.equals(sender)) continue;

            if (senderIsBot) {
                // Bot-to-bot conversations are allowed, but not endless loops.
                if (random.nextInt(100) < 28 &&
                        bot.getLocation().distanceSquared(sender.getLocation()) <= 2500) {
                    engage(bot, sender, message, data, false);
                }
                continue;
            }

            double distance = bot.getLocation().distanceSquared(sender.getLocation());

            // Normal conversation range: 50 blocks.
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
                    data.fatigueUntil = System.currentTimeMillis()
                            + randomBetween(25000, 55000);

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

            // Sometimes join nearby conversations naturally.
            if (!isCombat(bot) && random.nextInt(100) < 9) {
                engage(bot, sender, message, data, false);
            }
        }
    }

    private void engage(
            Player bot,
            Player target,
            String message,
            BotData data,
            boolean direct
    ) {
        data.conversationTarget = target.getUniqueId();
        data.lastInteraction = System.currentTimeMillis();

        if (data.conversationLines == 0) {
            data.conversationLines = 1;
        }

        String reply = generateReply(bot, target, message, data, direct);

        if (reply == null || reply.isBlank()) return;

        boolean flowState = data.conversationLines >= 2 &&
                System.currentTimeMillis() < data.fatigueUntil + 100000;

        scheduleReply(bot, target, data, reply, flowState);
    }

    private void scheduleReply(
            Player bot,
            Player target,
            BotData data,
            String reply,
            boolean flowState
    ) {
        BukkitTask old = pendingReplies.remove(bot.getUniqueId());
        if (old != null) old.cancel();

        long delay;

        if (flowState) {
            delay = 0;
        } else if (isCombat(bot)) {
            // Combat replies are fast.
            delay = randomBetween(4, 20);
        } else {
            // Normal replies: 1–5 seconds.
            delay = randomBetween(20, 100);
        }

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            pendingReplies.remove(bot.getUniqueId());

            if (!bot.isOnline()) return;
            if (!target.isOnline()) return;
            if (!bots.containsKey(bot.getUniqueId())) return;

            // Keep ordinary conversation within 50 blocks.
            if (bot.getLocation().distanceSquared(target.getLocation()) > 2500) {
                return;
            }

            long now = System.currentTimeMillis();

            if (!flowState && now - data.lastSpoke < 800) return;

            String finalReply = avoidRepeat(data, reply);
            if (finalReply == null) return;

            data.lastSpoke = now;
            data.recentPhrases.addLast(finalReply.toLowerCase(Locale.ROOT));

            while (data.recentPhrases.size() > 45) {
                data.recentPhrases.removeFirst();
            }

            bot.chat(finalReply);

        }, delay);

        pendingReplies.put(bot.getUniqueId(), task);
    }

    private boolean isAddressed(String message, String name) {
        String text = normalize(message);
        String username = normalize(name);

        if (username.isEmpty()) return false;
        if (text.contains(username)) return true;

        // Recognize shortened names and recognizable name fragments.
        Set<String> fragments = new HashSet<>();

        if (username.length() >= 4) {
            fragments.add(username.substring(0, 4));
            fragments.add(username.substring(0, Math.min(5, username.length())));
            fragments.add(username.substring(username.length() - 4));
            fragments.add(username.substring(username.length() - 3));
        } else {
            fragments.add(username);
        }

        for (String fragment : fragments) {
            if (fragment.length() >= 3 &&
                    text.matches(".*\\b" + java.util.regex.Pattern.quote(fragment) + "\\b.*")) {
                return true;
            }
        }

        return false;
    }

    private String generateReply(
            Player bot,
            Player target,
            String message,
            BotData data,
            boolean direct
    ) {
        String text = message.toLowerCase(Locale.ROOT);
        String style = data.style;

        if (containsAny(text, "not you", "not talking to you",
                "not you bro", "not you twin", "not talking about you")) {
            return pick(
                    "oh mb",
                    "my fault twin",
                    "damn i thought you meant me",
                    "aight carry on",
                    "mb gang i wasnt listening",
                    "oh thats my bad",
                    "nvm then",
                    "i'll mind my business",
                    "my fault bro",
                    "aight i gotchu"
            );
        }

        if (containsAny(text, "stfu", "shut up", "shut yo",
                "shut your mouth", "shut it")) {
            if (style.equals("supernice")) {
                return pick("damn okay", "my bad bro", "alright i'll chill",
                        "sorry twin", "okay okay");
            }

            if (style.equals("dry")) {
                return pick("ok", "damn", "alright", "whatever",
                        "you done?");
            }

            return pick(
                    "stfu twink",
                    "damn what did i do",
                    "bro woke up angry",
                    "you good twin",
                    "okay my fault gang",
                    "who hurt you",
                    "relaxxxx",
                    "bro is NOT happy",
                    "why you so mad",
                    "alright bro damn",
                    "you got it",
                    "i literally did nothing",
                    "damn chill",
                    "bro pressed for what",
                    "be nice to me twin",
                    "what is your problem",
                    "you woke up and chose violence",
                    "okay boss",
                    "my fault og",
                    "damn bro"
            );
        }

        if (containsAny(text, "hello", "hey", "yo", "wsp",
                "sup", "wassup", "what's up", "whats up")) {
            return greeting(style);
        }

        if (containsAny(text, "how are you", "how r u", "wyd", "what are you doing")) {
            return pick(
                    "chilling rn",
                    "not much honestly",
                    "just existing",
                    "trying not to die",
                    "standing around",
                    "nothing crazy wbu",
                    "im good bro",
                    "just vibing",
                    "looking for something to do",
                    "bored as hell"
            );
        }

        if (containsAny(text, "lol", "lmao", "lmfao", "haha", "help")) {
            return pick(
                    "LMAOO",
                    "bro what",
                    "nah thats crazy",
                    "HELP",
                    "im crying",
                    "you are not serious",
                    "LMFAOOO",
                    "bro please",
                    "thats actually funny",
                    "nahhh"
            );
        }

        if (containsAny(text, "are we", "toon force", "mog", "mogging")) {
            return pick(
                    "are we really doing this",
                    "bro thinks hes him",
                    "toon force is crazy",
                    "we are NOT that guy",
                    "nah we are finished",
                    "are we nex right now",
                    "bro is in his prime",
                    "thats actually insane",
                    "you cannot be serious",
                    "twin is ascending"
            );
        }

        if (containsAny(text, "where are you", "coords", "coordinates",
                "where u at", "where you at")) {
            Location loc = bot.getLocation();
            return "im at " + loc.getBlockX() + " " +
                    loc.getBlockY() + " " + loc.getBlockZ();
        }

        if (containsAny(text, "anyone have", "anyone got",
                "does anyone have", "who has", "who got")) {
            String resource = requestedMaterial(text);

            if (resource != null) {
                Material material = Material.matchMaterial(resource);

                if (material != null && bot.getInventory().contains(material)) {
                    return pick(
                            "i got some",
                            "yeah i have that",
                            "i got a bit",
                            "i can give you some",
                            "how much you need"
                    );
                }

                return pick(
                        "i dont have any rn",
                        "nah im broke",
                        "i need that too",
                        "wish i had some",
                        "not me bro",
                        "im looking for that too"
                );
            }

            return pick(
                    "what do you need",
                    "what item",
                    "depends what you need",
                    "i might have it",
                    "what are you looking for"
            );
        }

        if (containsAny(text, "thank you", "thanks", "ty", "thx")) {
            return pick("np", "no problem", "gotchu", "all good",
                    "anytime twin", "dont mention it");
        }

        if (containsAny(text, "sorry", "my bad", "mb")) {
            return pick("you good", "all good bro", "dw about it",
                    "its fine", "no worries", "fairs");
        }

        if (containsAny(text, "anime", "naruto", "sasuke", "one piece",
                "gojo", "jujutsu", "anime")) {
            return pick(
                    "naruto was actually peak",
                    "bro sasuke was doing too much",
                    "gojo fans are never beating the allegations",
                    "that arc was crazy",
                    "i need to watch that",
                    "nah that character is cold",
                    "anime debates never end",
                    "bro just put me on"
            );
        }

        if (containsAny(text, "fight", "pvp", "combo", "crystal",
                "totem", "mace", "sword", "axe")) {
            return pick(
                    "pvp is actually fun when you arent getting jumped",
                    "bro just dont get comboed",
                    "im not losing my gear today",
                    "that was a clean fight",
                    "nah i would win",
                    "we need a rematch",
                    "bro got folded",
                    "im staying out of that"
            );
        }

        if (containsAny(text, "what", "why", "how", "who", "when",
                "where", "do you", "are you", "can you")) {
            return questionReply(text, style);
        }

        if (style.equals("dry")) {
            return pick("yeah", "nah", "maybe", "idk",
                    "fairs", "real", "ok", "true", "sure", "probably");
        }

        if (style.equals("supernice")) {
            return pick(
                    "yeah of course",
                    "thats fair honestly",
                    "i get what you mean",
                    "you good bro",
                    "im listening",
                    "thats actually nice",
                    "yeah im down",
                    "good point twin"
            );
        }

        if (style.equals("toxic")) {
            return pick(
                    "bro what are you talking about",
                    "you sure about that",
                    "thats a crazy take",
                    "nah you are finished",
                    "keep talking",
                    "you got it lil bro",
                    "thats embarrassing",
                    "bro is yapping"
            );
        }

        if (style.equals("sus")) {
            return pick(
                    "ayo why you say it like that",
                    "pause",
                    "bro what",
                    "thats kinda crazy",
                    "you didnt have to say all that",
                    "sus behavior",
                    "im watching you",
                    "AYO"
            );
        }

        if (style.equals("brainrot")) {
            return pick(
                    "twin is NOT cooking",
                    "bro is actually cooked",
                    "nah thats wild",
                    "we got a yapper",
                    "absolute cinema",
                    "bro has negative aura",
                    "this is not real",
                    "im crying",
                    "chat is this real",
                    "nah gang"
            );
        }

        return pick(
                "fairs",
                "real",
                "nah fr",
                "i get you",
                "thats crazy",
                "what you mean",
                "bro honestly",
                "true twin",
                "im listening",
                "say that again",
                "wait what",
                "you might be right",
                "nah thats fair",
                "interesting",
                "im not even gonna lie",
                "thats actually wild",
                "i see what you mean",
                "bro has a point",
                "no way",
                "you serious?"
        );
    }

    private String questionReply(String text, String style) {
        if (containsAny(text, "do you like", "you like")) {
            return pick(
                    "depends honestly",
                    "yeah its pretty good",
                    "not really",
                    "i dont mind it",
                    "yeah im into that",
                    "nah not my thing",
                    "sometimes"
            );
        }

        if (containsAny(text, "are you real", "are you a bot")) {
            return pick(
                    "bro what",
                    "im literally standing here",
                    "you tell me",
                    "thats a crazy accusation",
                    "nah im just chilling",
                    "why would you ask me that"
            );
        }

        if (containsAny(text, "why")) {
            return pick(
                    "because why not",
                    "idk honestly",
                    "thats what im saying",
                    "good question",
                    "dont ask me bro",
                    "i was wondering the same thing"
            );
        }

        return pick(
                "idk what do you think",
                "maybe",
                "what do you mean exactly",
                "honestly no idea",
                "depends",
                "probably",
                "thats a good question",
                "you tell me",
                "i was thinking the same thing"
        );
    }

    private String greeting(String style) {
        if (style.equals("dry")) {
            return pick("yo", "wsp", "sup", "hey", "what");
        }

        return pick(
                "yo",
                "wsp twin",
                "ay whats good",
                "sup bro",
                "yo gang",
                "what's up",
                "wsp",
                "hey",
                "who called me",
                "what you need",
                "ayyy",
                "yo yo",
                "whats happening",
                "what we doing",
                "im here",
                "wassup",
                "what's good",
                "how we doing",
                "yo twin",
                "ay bro"
        );
    }

    private String avoidRepeat(BotData data, String reply) {
        if (!data.recentPhrases.contains(reply.toLowerCase(Locale.ROOT))) {
            return reply;
        }

        // Rebuild a variation instead of repeating an exact line.
        for (int i = 0; i < 12; i++) {
            String alternative = pick(
                    "nah fr",
                    "fairs honestly",
                    "bro what",
                    "you get me",
                    "im saying",
                    "thats crazy",
                    "real",
                    "i hear you",
                    "no way",
                    "thats fair",
                    "you serious",
                    "im not even joking",
                    "wait actually",
                    "lowkey true",
                    "nah thats wild",
                    "i get it",
                    "exactly",
                    "thats what im saying",
                    "you know what i mean",
                    "bro honestly"
            );

            if (!data.recentPhrases.contains(alternative.toLowerCase(Locale.ROOT))) {
                return alternative;
            }
        }

        return null;
    }

    private void ambientTick() {
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, BotData> entry : bots.entrySet()) {
            Player bot = Bukkit.getPlayer(entry.getKey());
            BotData data = entry.getValue();

            if (bot == null || !bot.isOnline()) continue;
            if (isCombat(bot)) continue;

            // Occasionally notice players at 50–100 blocks.
            if (now - lastSpotMessage.getOrDefault(bot.getUniqueId(), 0L) > 60000) {
                for (Player nearby : Bukkit.getOnlinePlayers()) {
                    if (nearby.equals(bot) || bots.containsKey(nearby.getUniqueId())) continue;

                    double distance = bot.getLocation().distanceSquared(nearby.getLocation());

                    if (distance > 2500 && distance <= 10000 &&
                            bot.hasLineOfSight(nearby) && random.nextInt(100) < 7) {

                        String mock = mockName(nearby.getName());

                        String line = pick(
                                "i can see you " + nearby.getName(),
                                "bro i see you over there " + nearby.getName(),
                                "you thought you were sneaky " + nearby.getName(),
                                "are we " + mock + " right now",
                                mock + " behavior",
                                "yo " + nearby.getName() + " i see you"
                        );

                        bot.chat(line);
                        lastSpotMessage.put(bot.getUniqueId(), now);
                        break;
                    }
                }
            }

            // Occasional TPA requests to random players.
            if (now - data.lastSpoke > 120000 && random.nextInt(100) < 3) {
                requestRandomTeleport(bot, data);
            }
        }
    }

    private void requestRandomTeleport(Player bot, BotData data) {
        List<Player> candidates = new ArrayList<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.equals(bot)) continue;
            if (bots.containsKey(player.getUniqueId())) continue;
            candidates.add(player);
        }

        if (candidates.isEmpty()) return;

        Player target = candidates.get(random.nextInt(candidates.size()));

        String message = pick(
                "yo can i tp",
                "can i chill with you",
                "accept twin",
                "accept bro",
                "accept twinny",
                "lemme come over",
                "yo send the accept",
                "trust accept",
                "can i come to you",
                "im bored can i tp",
                "let me join you",
                "accept sahur",
                "yo can i come chill",
                "lemme pull up",
                "can i teleport over",
                "accept the request",
                "im tryna chill with you",
                "send that tp",
                "yo let me come over",
                "accept gang"
        );

        bot.chat(message);

        // Execute as the bot player, not as console.
        Bukkit.dispatchCommand(bot, "tpa " + target.getName());

        data.lastSpoke = System.currentTimeMillis();
    }

    private String mockName(String name) {
        if (name.length() <= 3) return name;

        String lower = name.toLowerCase(Locale.ROOT);

        if (lower.length() >= 6) {
            return lower.substring(lower.length() - 3);
        }

        return lower.substring(0, Math.min(4, lower.length()));
    }

    private boolean isCombat(Player player) {
        return combatUntil.getOrDefault(player.getUniqueId(), 0L)
                > System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player victim = event.getEntity() instanceof Player p ? p : null;

        Player attacker = null;

        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player p) {
            attacker = p;
        }

        long until = System.currentTimeMillis() + 12000;

        if (victim != null && bots.containsKey(victim.getUniqueId())) {
            combatUntil.put(victim.getUniqueId(), until);

            if (random.nextInt(100) < 65) {
                String reply = combatPhrase(true);
                if (random.nextBoolean()) reply = panicTyping(reply);

                BotData data = bots.get(victim.getUniqueId());
                Player target = attacker;

                if (target != null) {
                    scheduleReply(victim, target, data, reply, false);
                } else {
                    victim.chat(reply);
                }
            }
        }

        if (attacker != null && bots.containsKey(attacker.getUniqueId())) {
            combatUntil.put(attacker.getUniqueId(), until);

            if (victim != null && random.nextInt(100) < 35) {
                String reply = combatPhrase(false);
                if (random.nextBoolean()) reply = panicTyping(reply);

                attacker.chat(reply);
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (!bots.containsKey(player.getUniqueId())) return;

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;

            player.chat(pick(
                    "BRO",
                    "nah thats crazy",
                    "im actually finished",
                    "who jumped me",
                    "im never recovering",
                    "that was embarrassing",
                    "im getting my stuff back",
                    "nah run that back",
                    "I GOT FOLDED",
                    "bro where was my teammate"
            ));
        }, 20L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (getConfig().contains("bots." + player.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline()) return;
                loadBots();
            }, 20L);
        }
    }

    private String combatPhrase(boolean beingHit) {
        if (beingHit) {
            return pick(
                    "BRO IM GETTING COMBOED",
                    "HELP HELP",
                    "nah im low",
                    "BRO GET OFF ME",
                    "IM ACTUALLY COOKED",
                    "WHERE IS MY TEAM",
                    "WAIT WAIT",
                    "IM GETTING JUMPED",
                    "BRO CHILL",
                    "I NEED HELP",
                    "nah this is bad",
                    "IM RUNNING",
                    "BRO WHAT",
                    "GET ME OUT",
                    "im so dead"
            );
        }

        return pick(
                "get comboed",
                "nah you are finished",
                "bro cannot move",
                "that was clean",
                "stay there",
                "you are not escaping",
                "free combo",
                "bro got caught",
                "nah run",
                "too easy",
                "im on you",
                "you are cooked",
                "hold that",
                "get back here",
                "no way you survived that"
        );
    }

    private String panicTyping(String message) {
        return switch (random.nextInt(6)) {
            case 0 -> message.replace("BRO", "BROO");
            case 1 -> message.replace("HELP", "HELPP");
            case 2 -> message.replace("IM", "IMMM");
            case 3 -> message.replace("WAIT", "WAIIT");
            case 4 -> message.replace("GET", "GE T");
            default -> message + " BRO";
        };
    }

    private String requestedMaterial(String text) {
        if (containsAny(text, "wood", "logs", "planks")) return "OAK_LOG";
        if (containsAny(text, "iron")) return "IRON_INGOT";
        if (containsAny(text, "diamonds", "diamond")) return "DIAMOND";
        if (containsAny(text, "food", "bread")) return "BREAD";
        if (containsAny(text, "cobble", "cobblestone")) return "COBBLESTONE";
        if (containsAny(text, "gold")) return "GOLD_INGOT";
        if (containsAny(text, "arrows", "arrow")) return "ARROW";
        if (containsAny(text, "ender pearl", "pearls")) return "ENDER_PEARL";
        return null;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String pick(String... choices) {
        return choices[random.nextInt(choices.length)];
    }

    private long randomBetween(long min, long max) {
        return min + random.nextLong(max - min + 1);
    }
}
