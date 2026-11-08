
package com.jerinex.talkbot;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class TalkBotPlugin extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter {

    private static final Set<String> STYLES = Set.of(
            "dry", "sus", "toxic", "chill",
            "supernice", "brainrot", "chillbot1", "chillbot2"
    );

    private final Map<String, BotProfile> bots = new ConcurrentHashMap<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginCommand command = Objects.requireNonNull(getCommand("talkbot"));
        command.setExecutor(this);
        command.setTabCompleter(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getConfig().isConfigurationSection("registered-bots")) {
            for (String name : getConfig().getConfigurationSection("registered-bots").getKeys(false)) {
                String style = getConfig().getString("registered-bots." + name, "chill");

                if (!STYLES.contains(style.toLowerCase(Locale.ROOT))) {
                    style = "chill";
                }

                bots.put(key(name), new BotProfile(name, style));
            }
        }

        Bukkit.getScheduler().runTaskTimer(
                this,
                this::maybeStartBotConversation,
                200L,
                200L
        );

        getLogger().info("TalkBot enabled. Offline conversation engine loaded.");
    }

    @Override
    public void onDisable() {
        saveProfiles();
        bots.clear();
    }

    // =========================================================
    // COMMANDS
    // =========================================================

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 0) {
            sender.sendMessage("§e/talkbot <bot>");
            sender.sendMessage("§e/talkbot <bot> style <style>");
            sender.sendMessage("§e/talkbot <bot> off");
            sender.sendMessage("§e/talkbot list");
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            if (bots.isEmpty()) {
                sender.sendMessage("§7No TalkBots registered.");
                return true;
            }

            sender.sendMessage("§aRegistered TalkBots:");

            for (BotProfile profile : bots.values()) {
                sender.sendMessage("§f- " + profile.name + " §7(" + profile.style + ")");
            }

            return true;
        }

        String name = args[0];
        String id = key(name);

        if (args.length >= 2 && args[1].equalsIgnoreCase("off")) {
            BotProfile removed = bots.remove(id);

            getConfig().set("registered-bots." + name, null);
            saveConfig();

            sender.sendMessage(
                    removed == null
                            ? "§7That bot wasn't registered."
                            : "§cTalkBot disabled for " + removed.name
            );

            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("style")) {
            if (args.length < 3) {
                sender.sendMessage("§cAvailable styles: " + String.join(", ", STYLES));
                return true;
            }

            String style = args[2].toLowerCase(Locale.ROOT);

            if (!STYLES.contains(style)) {
                sender.sendMessage("§cAvailable styles: " + String.join(", ", STYLES));
                return true;
            }

            BotProfile profile = bots.get(id);

            if (profile == null) {
                Player online = Bukkit.getPlayerExact(name);

                if (online == null) {
                    sender.sendMessage("§cSpawn the bot first, then register it.");
                    return true;
                }

                profile = new BotProfile(online.getName(), style);
                bots.put(key(online.getName()), profile);
            } else {
                profile.style = style;
            }

            saveProfiles();

            sender.sendMessage("§a" + profile.name + "'s personality is now " + style + ".");
            return true;
        }

        Player player = Bukkit.getPlayerExact(name);

        if (player == null) {
            sender.sendMessage("§cI can't see " + name + " online. Spawn the PvpBot first.");
            return true;
        }

        bots.put(key(player.getName()), new BotProfile(player.getName(), "chill"));
        saveProfiles();

        sender.sendMessage("§aTalkBot enabled for " + player.getName() + ".");
        sender.sendMessage("§7Change its personality with /talkbot " + player.getName() + " style <style>");

        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            result.add("list");

            for (Player player : Bukkit.getOnlinePlayers()) {
                result.add(player.getName());
            }

            for (BotProfile profile : bots.values()) {
                result.add(profile.name);
            }

            return result;
        }

        if (args.length == 2) {
            return List.of("style", "off");
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("style")) {
            return new ArrayList<>(STYLES);
        }

        return Collections.emptyList();
    }

    // =========================================================
    // CHAT LISTENER
    // =========================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player speaker = event.getPlayer();

        String message = PlainTextComponentSerializer.plainText()
                .serialize(event.message())
                .trim();

        if (message.isEmpty()) return;

        if (getConfig().getBoolean("chat.ignore-commands", true)
                && message.startsWith("/")) {
            return;
        }

        UUID uuid = speaker.getUniqueId();
        String name = speaker.getName();
        String content = message;

        Bukkit.getScheduler().runTask(this, () -> {
            Player current = Bukkit.getPlayer(uuid);

            if (current == null) return;

            if (isBot(name)) {
                processBotConversation(name, content);
            } else {
                processPlayerMessage(name, content);
            }
        });
    }

    private void processPlayerMessage(String speaker, String original) {
        String message = normalize(original);

        if (message.isBlank()) return;

        for (BotProfile profile : bots.values()) {
            Player bot = Bukkit.getPlayerExact(profile.name);

            if (bot == null) continue;

            profile.remember(speaker + ": " + original);

            Decision decision = understand(profile, bot, speaker, message);

            if (decision != null) {
                scheduleReply(profile, decision.message, decision.delay);
            }
        }
    }

    private Decision understand(
            BotProfile profile,
            Player bot,
            String speaker,
            String message
    ) {
        boolean addressed = isAddressed(profile.name, message);

        // Resource requests.
        if (asksForResource(message)) {
            String material = requestedMaterial(message);

            if (material != null && hasMaterial(bot, material)) {
                if (addressed || random.nextDouble() < 0.20) {
                    int amount = countMaterial(bot, material);

                    String reply = amount == 1
                            ? "i got one " + pretty(material)
                            : "i got " + amount + " " + pretty(material);

                    return createDecision(profile, reply, 2, 8);
                }
            } else if (material != null && addressed) {
                return createDecision(
                        profile,
                        choose(profile, new String[]{
                                "nah im out of " + pretty(material),
                                "dont got any rn",
                                "i need some too",
                                "sorry bro, no " + pretty(material)
                        }),
                        2,
                        8
                );
            }
        }

        // Coordinate requests.
        if (asksForCoordinates(message) && addressed) {
            return createDecision(
                    profile,
                    "im at " + coordinates(bot),
                    2,
                    8
            );
        }

        // Help offers.
        if (offersHelp(message) && addressed) {
            return createDecision(
                    profile,
                    "bet, im at " + coordinates(bot),
                    3,
                    9
            );
        }

        // Ignore most messages not directed at this bot.
        if (!addressed) {
            if (isFunny(message) && random.nextDouble() < 0.08) {
                return createDecision(profile, jokeReply(profile), 5, 15);
            }

            if (isGreeting(message) && random.nextDouble() < 0.05) {
                return createDecision(profile, greetingReply(profile), 5, 12);
            }

            if (random.nextDouble() < getConfig().getDouble(
                    "chat.ambient-reply-chance", 0.01)) {
                return createDecision(profile, ambientReply(profile), 8, 20);
            }

            return null;
        }

        // Direct conversation.
        if (isGreeting(message)) {
            return createDecision(profile, greetingReply(profile), 2, 8);
        }

        if (isHowAreYou(message)) {
            return createDecision(profile, smallTalkReply(profile), 2, 8);
        }

        if (asksName(message)) {
            return createDecision(profile, "im " + profile.name + " bro", 2, 7);
        }

        if (isInsult(message)) {
            return createDecision(profile, insultReply(profile), 2, 8);
        }

        if (isFunny(message)) {
            return createDecision(profile, jokeReply(profile), 2, 8);
        }

        if (isMinecraftQuestion(message)) {
            return createDecision(
                    profile,
                    minecraftReply(profile, message),
                    3,
                    10
            );
        }

        if (isOpinionQuestion(message)) {
            return createDecision(
                    profile,
                    opinionReply(profile, message),
                    3,
                    10
            );
        }

        return createDecision(
                profile,
                generalReply(profile, message),
                3,
                10
        );
    }

    private Decision createDecision(
            BotProfile profile,
            String message,
            int minDelay,
            int maxDelay
    ) {
        long now = System.currentTimeMillis();

        if (now - profile.lastSpokeAt
                < getConfig().getLong("chat.cooldown-seconds", 5) * 1000L) {
            return null;
        }

        int delay = minDelay + random.nextInt(maxDelay - minDelay + 1);

        return new Decision(message, delay);
    }

    // =========================================================
    // BOT REPLIES
    // =========================================================

    private void scheduleReply(
            BotProfile profile,
            String message,
            int delay
    ) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player bot = Bukkit.getPlayerExact(profile.name);

            if (bot == null || !bots.containsKey(key(profile.name))) return;

            long now = System.currentTimeMillis();

            if (now - profile.lastSpokeAt
                    < getConfig().getLong("chat.cooldown-seconds", 5) * 1000L) {
                return;
            }

            String reply = sanitize(message);

            if (reply.isBlank()) return;

            int max = getConfig().getInt("chat.max-message-characters", 160);

            if (reply.length() > max) {
                reply = reply.substring(0, max).trim();
            }

            profile.lastSpokeAt = now;
            profile.remember(profile.name + ": " + reply);

            try {
                bot.chat(reply);
            } catch (Throwable error) {
                getLogger().warning("Could not make " + profile.name + " chat: " + error.getMessage());
            }

        }, Math.max(1, delay));
    }

    private String greetingReply(BotProfile p) {
        return choose(p, switch (p.style) {
            case "dry" -> new String[]{"yo", "wsp", "sup", "what"};
            case "supernice" -> new String[]{"yooo what's good", "hey how you doing", "yo yo"};
            case "brainrot" -> new String[]{"yooo twin", "wsp gang", "AYO", "what's good cuh"};
            case "toxic" -> new String[]{"what you want", "yo", "wsp"};
            case "sus" -> new String[]{"yo", "wsp", "why you calling me"};
            case "chillbot1" -> new String[]{"yo bro", "wsp", "what's up"};
            case "chillbot2" -> new String[]{"yooo", "ay what's good", "sup"};
            default -> new String[]{"yo", "wsp", "yooo", "what's up", "sup"};
        });
    }

    private String smallTalkReply(BotProfile p) {
        return choose(p, switch (p.style) {
            case "dry" -> new String[]{"im good", "fine", "chilling"};
            case "supernice" -> new String[]{"im good bro, how about you", "doing pretty good"};
            case "brainrot" -> new String[]{"im chilling twin", "we up", "still alive somehow"};
            case "toxic" -> new String[]{"better than you probably", "im good"};
            case "sus" -> new String[]{"im good bro why", "chilling, you?"};
            default -> new String[]{"im good, you?", "chilling rn", "im alright", "can't complain"};
        });
    }

    private String insultReply(BotProfile p) {
        return choose(p, switch (p.style) {
            case "dry" -> new String[]{"ok", "alright", "if you say so"};
            case "supernice" -> new String[]{"damn what did i do to you", "ill let you have that one"};
            case "toxic" -> new String[]{"you type more than you fight", "say that after you win one", "bro is brave in chat"};
            case "brainrot" -> new String[]{"bro woke up and chose yap", "you got it twin", "crazy statement"};
            case "sus" -> new String[]{"why you obsessed with me", "bro wants my attention"};
            default -> new String[]{"bro what did i do", "alright man", "you good?"};
        });
    }

    private String jokeReply(BotProfile p) {
        return choose(p, switch (p.style) {
            case "dry" -> new String[]{"...", "not bad", "i hate that i laughed"};
            case "brainrot" -> new String[]{"HELP", "LMAOOO", "nah thats crazy", "BRO"};
            case "sus" -> new String[]{"AYO pause", "nah you did not just say that", "sus"};
            default -> new String[]{"LMAO", "nah thats funny", "bro", "HELP", "that was actually good"};
        });
    }

    private String generalReply(BotProfile p, String msg) {
        if (contains(msg, "fight", "1v1", "duel")) {
            return choose(p, new String[]{"bet", "when", "what kit", "you first"});
        }

        if (contains(msg, "come here", "tp", "teleport")) {
            return choose(p, new String[]{"where to", "what for", "gimme a sec", "coords?"});
        }

        if (contains(msg, "build", "building")) {
            return choose(p, new String[]{"what are you building", "that could be cool", "lemme see when its done"});
        }

        if (contains(msg, "bored")) {
            return choose(p, new String[]{"same, we should do something", "go start a war or something", "build something stupid"});
        }

        if (contains(msg, "help")) {
            return choose(p, new String[]{"with what", "what happened", "what do you need"});
        }

        if (msg.contains("?")) {
            return choose(p, new String[]{"maybe", "depends", "idk honestly", "probably", "what do you think"});
        }

        return choose(p, switch (p.style) {
            case "dry" -> new String[]{"yeah", "maybe", "idk", "fair"};
            case "supernice" -> new String[]{"oh fr? that's cool", "i got you", "yeah that makes sense"};
            case "brainrot" -> new String[]{"twin what", "nah fr", "lowkey", "thats wild"};
            case "toxic" -> new String[]{"you sure about that", "bold claim", "prove it"};
            case "sus" -> new String[]{"ayo", "bro what", "thats kinda weird"};
            case "chillbot1" -> new String[]{"fr", "yeah probably", "fair enough"};
            case "chillbot2" -> new String[]{"lowkey true", "i get you", "real"};
            default -> new String[]{"fr", "yeah probably", "fair enough", "lowkey true", "i get you"};
        });
    }

    // =========================================================
    // MINECRAFT KNOWLEDGE
    // =========================================================

    private String minecraftReply(BotProfile p, String msg) {
        if (contains(msg, "best armor", "strongest armor")) {
            return "netherite is the strongest normal armor tier, and protection enchants matter a lot";
        }

        if (contains(msg, "best sword", "strongest sword")) {
            return "netherite sword with sharpness is the usual answer, depends on the pvp rules";
        }

        if (contains(msg, "diamond")) {
            if (contains(msg, "what y", "which y", "best y", "where find")) {
                return "around y -59 is the usual diamond mining level";
            }

            if (contains(msg, "how get", "where get", "find")) {
                return "mine below y 16, around y -59 is usually good. fortune helps";
            }
        }

        if (contains(msg, "iron") && contains(msg, "what y", "where find", "best y")) {
            return "iron is common around y 16, and high mountains are good too";
        }

        if (contains(msg, "ancient debris")) {
            return "around y 15 in the nether is the usual ancient debris level";
        }

        if (contains(msg, "netherite") && contains(msg, "craft", "make", "upgrade")) {
            return "use a smithing table with a netherite upgrade template and a netherite ingot";
        }

        if (contains(msg, "elytra")) {
            return "you can't craft elytra, find them on end ships";
        }

        if (contains(msg, "end crystal")) {
            return "end crystals use glass, an eye of ender and a ghast tear";
        }

        if (contains(msg, "enchant")) {
            return "you need an enchanting table, lapis and 15 bookshelves for level 30 enchants";
        }

        if (contains(msg, "warden", "wither", "ender dragon")) {
            return "those bosses can get messy. bring good gear, food and a plan";
        }

        if (contains(msg, "crystal pvp", "cpvp")) {
            return "crystal pvp is mostly timing, spacing and knowing when to place and break";
        }

        if (contains(msg, "mace")) {
            return "mace damage scales with fall distance, wind charges make some nasty setups";
        }

        if (contains(msg, "totem")) {
            return "totems save you from dying once, but watch out for the next hit";
        }

        if (contains(msg, "redstone", "farm")) {
            return "depends on the farm. what are you trying to build?";
        }

        if (contains(msg, "villager")) {
            return "villagers are useful for trading, just lock in the trades you actually need";
        }

        if (contains(msg, "pvp")) {
            return choose(p, new String[]{
                    "depends on the kit and version",
                    "spacing and timing matter more than people think",
                    "w tapping and strafing help a lot"
            });
        }

        return choose(p, new String[]{
                "depends on the version and server rules",
                "yeah that's a Minecraft thing for sure",
                "what part are you asking about?",
                "there's a few ways to do that"
        });
    }

    private String opinionReply(BotProfile p, String msg) {
        if (contains(msg, "java or bedrock", "better java", "minecraft better")) {
            return "java for pvp and servers, bedrock is nice for crossplay. depends what you grew up on";
        }

        if (contains(msg, "naruto", "anime")) {
            return choose(p, new String[]{
                    "naruto has some insane fights",
                    "depends on the arc, some of it is peak",
                    "i still think the character fights carry"
            });
        }

        if (contains(msg, "football", "soccer")) {
            return choose(p, new String[]{
                    "football debates never end",
                    "depends which team you're talking about",
                    "who's your team?"
            });
        }

        if (contains(msg, "basketball", "nba")) {
            return choose(p, new String[]{
                    "basketball takes get heated for no reason",
                    "who's your favorite player?",
                    "depends what era you're talking about"
            });
        }

        return choose(p, new String[]{
                "honestly depends",
                "i can see both sides",
                "nah i have a different take but go on",
                "not sure yet"
        });
    }

    private String ambientReply(BotProfile p) {
        return choose(p, switch (p.style) {
            case "dry" -> new String[]{"anyone on", "quiet server"};
            case "brainrot" -> new String[]{"twin this server is quiet", "what are we doing gang"};
            case "supernice" -> new String[]{"anyone need a hand with anything?", "hope everyone's doing good"};
            default -> new String[]{"anyone doing anything", "what's everyone up to", "anyone wanna play"};
        });
    }

    // =========================================================
    // COMBAT CHAT REACTIONS
    // =========================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player bot = asBot(event.getEntity());
        boolean victim = bot != null;

        if (bot == null) {
            bot = asBot(event.getDamager());
        }

        if (bot == null) return;

        BotProfile profile = bots.get(key(bot.getName()));
        if (profile == null) return;

        long now = System.currentTimeMillis();

        if (now - profile.lastCombatAt
                < getConfig().getLong("combat.cooldown-seconds", 5) * 1000L) {
            return;
        }

        if (random.nextDouble() > getConfig().getDouble("combat.react-chance", 0.48)) {
            return;
        }

        String reply = combatReply(profile, victim, bot.getHealth());

        if (reply == null) return;

        profile.lastCombatAt = now;
        scheduleCombatReply(profile, reply, 1 + random.nextInt(5));
    }

    private String combatReply(BotProfile p, boolean victim, double health) {
        boolean low = health <= 6.0;

        if (victim && low) {
            return choose(p, switch (p.style) {
                case "dry" -> new String[]{"im low.", "not good"};
                case "toxic" -> new String[]{"bro is actually sweating", "get off me for a second"};
                case "brainrot" -> new String[]{"BRO IM ONE", "nah twin chill", "HELP"};
                default -> new String[]{"yo chill", "im getting cooked", "bro relax", "im low"};
            });
        }

        if (victim) {
            return choose(p, switch (p.style) {
                case "dry" -> new String[]{"ow", "really"};
                case "toxic" -> new String[]{"that's all you got?", "you need that many hits?"};
                case "brainrot" -> new String[]{"AYO", "nah", "bro jumped me"};
                default -> new String[]{"yo", "bro", "watch it", "nah you got me"};
            });
        }

        return choose(p, switch (p.style) {
            case "dry" -> new String[]{"ok", "there"};
            case "toxic" -> new String[]{"light work", "sit down", "you walked into that"};
            case "brainrot" -> new String[]{"GET CLIPPED", "twin got sent", "nahhh"};
            default -> new String[]{"got you", "nice", "hold that", "lets go"};
        });
    }

    private void scheduleCombatReply(BotProfile profile, String reply, int delay) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player bot = Bukkit.getPlayerExact(profile.name);
            if (bot == null || !bots.containsKey(key(profile.name))) return;

            try {
                bot.chat(sanitize(reply));
                profile.lastSpokeAt = System.currentTimeMillis();
                profile.remember(profile.name + ": " + reply);
            } catch (Throwable error) {
                getLogger().warning("Combat chat failed for " + profile.name);
            }
        }, delay);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        BotProfile profile = bots.get(key(event.getEntity().getName()));

        if (profile == null || random.nextDouble() > 0.85) return;

        String reply = choose(profile, switch (profile.style) {
            case "dry" -> new String[]{"bruh.", "unfortunate.", "lag. obviously."};
            case "toxic" -> new String[]{"nah that was a scam death", "im getting that back", "you got lucky bro"};
            case "supernice" -> new String[]{"gg, ill get it next time", "all good, happens"};
            case "brainrot" -> new String[]{"BRO", "nahhh", "im actually sick"};
            default -> new String[]{"BRO", "nahhh", "how did i die there", "run that back"};
        });

        scheduleCombatReply(profile, reply, 3);
    }

    // =========================================================
    // BOT-TO-BOT CHAT
    // =========================================================

    private void maybeStartBotConversation() {
        if (bots.size() < 2) return;

        if (random.nextDouble() > getConfig().getDouble("social.bot-chat-chance", 0.035)) {
            return;
        }

        List<BotProfile> online = new ArrayList<>();

        for (BotProfile profile : bots.values()) {
            if (Bukkit.getPlayerExact(profile.name) != null) {
                online.add(profile);
            }
        }

        if (online.size() < 2) return;

        Collections.shuffle(online);

        BotProfile first = online.get(0);
        BotProfile second = online.get(1);

        long now = System.currentTimeMillis();

        if (now - first.lastSpokeAt < 20000L) return;
        if (now - first.lastBotConversationAt < 90000L) return;

        String opener = choose(first, switch (first.style) {
            case "dry" -> new String[]{
                    "you doing anything or just standing there",
                    "this server is quiet",
                    "what are you even building"
            };
            case "toxic" -> new String[]{
                    second.name + " you still getting folded in pvp?",
                    "you tryna fight or what"
            };
            case "brainrot" -> new String[]{
                    "twin this server is NOT real",
                    "gang what are we even doing rn",
                    "nah this world is cooked"
            };
            case "supernice" -> new String[]{
                    "yo " + second.name + ", how's your day going",
                    "anyone need help with anything?"
            };
            default -> new String[]{
                    "yo " + second.name + ", what you up to",
                    "anyone wanna do something",
                    "this server needs something to happen"
            };
        });

        first.lastBotConversationAt = now;
        scheduleReply(first, opener, 5 + random.nextInt(12));
    }

    private void processBotConversation(String speakerName, String message) {
        String normalized = normalize(message);

        for (BotProfile profile : bots.values()) {
            if (profile.name.equalsIgnoreCase(speakerName)) continue;

            Player bot = Bukkit.getPlayerExact(profile.name);
            if (bot == null) continue;

            boolean addressed = isAddressed(profile.name, normalized);

            if (!addressed && random.nextDouble() > 0.12) continue;

            long now = System.currentTimeMillis();

            if (now - profile.lastSpokeAt < 12000L) continue;
            if (now - profile.lastBotConversationAt < 25000L) continue;

            String reply = choose(profile, switch (profile.style) {
                case "dry" -> new String[]{"yeah", "ok", "fair", "what"};
                case "toxic" -> new String[]{"bro nobody asked", "you sure about that", "nah you're washed"};
                case "brainrot" -> new String[]{"twin what", "HELP", "nah fr", "bro"};
                case "supernice" -> new String[]{"yeah fr", "that's actually cool", "what are you guys doing?"};
                default -> new String[]{"fr", "LMAO", "what do you mean", "yeah probably", "nah thats crazy"};
            });

            profile.lastBotConversationAt = now;
            scheduleReply(profile, reply, 3 + random.nextInt(8));
            break;
        }
    }

    // =========================================================
    // RESOURCE / INVENTORY HELPERS
    // =========================================================

    private boolean asksForResource(String msg) {
        return contains(
                msg,
                "anyone got", "anyone have", "does anyone have",
                "who has", "need some", "need a", "can i get",
                "can someone give", "got any", "have any",
                "looking for"
        );
    }

    private String requestedMaterial(String msg) {
        String[][] aliases = {
                {"wood", "OAK_LOG"},
                {"logs", "OAK_LOG"},
                {"planks", "OAK_PLANKS"},
                {"cobble", "COBBLESTONE"},
                {"cobblestone", "COBBLESTONE"},
                {"stone", "STONE"},
                {"dirt", "DIRT"},
                {"sand", "SAND"},
                {"glass", "GLASS"},
                {"iron", "IRON_INGOT"},
                {"diamonds", "DIAMOND"},
                {"diamond", "DIAMOND"},
                {"gold", "GOLD_INGOT"},
                {"emeralds", "EMERALD"},
                {"emerald", "EMERALD"},
                {"obsidian", "OBSIDIAN"},
                {"food", "COOKED_BEEF"},
                {"bread", "BREAD"},
                {"arrows", "ARROW"},
                {"ender pearls", "ENDER_PEARL"},
                {"ender pearl", "ENDER_PEARL"},
                {"totems", "TOTEM_OF_UNDYING"},
                {"totem", "TOTEM_OF_UNDYING"},
                {"gapples", "GOLDEN_APPLE"},
                {"golden apple", "GOLDEN_APPLE"},
                {"netherite", "NETHERITE_INGOT"}
        };

        for (String[] alias : aliases) {
            if (msg.contains(alias[0])) return alias[1];
        }

        return null;
    }

    private boolean hasMaterial(Player player, String materialName) {
        return countMaterial(player, materialName) > 0;
    }

    private int countMaterial(Player player, String materialName) {
        Material material = Material.matchMaterial(materialName);

        if (material == null) return 0;

        int amount = 0;

        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) {
                amount += item.getAmount();
            }
        }

        return amount;
    }

    // =========================================================
    // DETECTION HELPERS
    // =========================================================

    private boolean isAddressed(String botName, String message) {
        String name = normalize(botName);

        return message.matches(".*\\b" + Pattern.quote(name) + "\\b.*")
                || message.startsWith("@" + name);
    }

    private boolean isGreeting(String msg) {
        return msg.matches(
                "^(yo+|hey+|hi+|hello+|sup|wsp|what'?s up|good morning|good evening)(\\s+.*)?$"
        );
    }

    private boolean isHowAreYou(String msg) {
        return contains(msg,
                "how are you", "how you doing",
                "you good", "hru", "wyd"
        );
    }

    private boolean isInsult(String msg) {
        return contains(msg,
                "you suck", "you're trash", "ur trash",
                "stupid", "idiot", "washed", "ez"
        );
    }

    private boolean isFunny(String msg) {
        return contains(msg,
                "why did", "knock knock", "ligma",
                "deez", "lmao", "lmfao",
                "haha", "hahaha", "ayoo"
        );
    }

    private boolean isMinecraftQuestion(String msg) {
        return contains(msg,
                "minecraft", "craft", "diamond",
                "netherite", "warden", "wither",
                "ender dragon", "elytra", "redstone",
                "enchant", "pvp", "crystal",
                "mace", "totem", "farm",
                "villager", "coords"
        );
    }

    private boolean isOpinionQuestion(String msg) {
        return contains(msg,
                "what do you think", "your opinion",
                "who is better", "which is better",
                "do you like", "favorite", "favourite",
                "naruto", "anime", "football",
                "soccer", "basketball", "nba"
        );
    }

    private boolean isFunnyMessage(String msg) {
        return contains(msg,
                "bro what", "nahhh", "no way",
                "what the", "lmao", "lmfao",
                "hahaha", "ayo"
        );
    }

    private boolean asksForCoordinates(String msg) {
        return contains(msg,
                "your coords", "your coordinates",
                "where are you", "where u at",
                "where you at", "send coords",
                "drop coords"
        );
    }

    private boolean offersHelp(String msg) {
        return contains(msg,
                "i can help", "ill help", "i'll help",
                "coming to help", "where do you need",
                "need help"
        );
    }

    private boolean asksName(String msg) {
        return contains(msg,
                "who are you", "whats your name",
                "what is your name"
        );
    }

    // =========================================================
    // GENERAL HELPERS
    // =========================================================

    private String choose(BotProfile profile, String[] options) {
        if (options.length == 1) return options[0];

        String previous = profile.lastPhrase;

        for (int i = 0; i < 8; i++) {
            String candidate = options[random.nextInt(options.length)];

            if (!candidate.equalsIgnoreCase(previous)) {
                profile.lastPhrase = candidate;
                return candidate;
            }
        }

        return options[random.nextInt(options.length)];
    }

    private String coordinates(Player player) {
        return player.getLocation().getBlockX() + " "
                + player.getLocation().getBlockY() + " "
                + player.getLocation().getBlockZ();
    }

    private String pretty(String material) {
        return material.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String normalize(String input) {
        return input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9@'!? ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String sanitize(String input) {
        if (input == null) return "";

        return input.replaceAll("[\\r\\n]+", " ")
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
    }

    private boolean contains(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }

        return false;
    }

    private String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private boolean isBot(String name) {
        return bots.containsKey(key(name));
    }

    private Player asBot(Entity entity) {
        if (!(entity instanceof Player player)) return null;

        return isBot(player.getName()) ? player : null;
    }

    private void saveProfiles() {
        getConfig().set("registered-bots", null);

        for (BotProfile profile : bots.values()) {
            getConfig().set(
                    "registered-bots." + profile.name,
                    profile.style
            );
        }

        saveConfig();
    }

    // =========================================================
    // DATA CLASSES
    // =========================================================

    private static final class Decision {
        final String message;
        final int delay;

        Decision(String message, int delay) {
            this.message = message;
            this.delay = delay;
        }
    }

    private static final class BotProfile {
        final String name;

        volatile String style;

        final Deque<String> history = new ArrayDeque<>();

        String lastPhrase = "";
        long lastSpokeAt;
        long lastCombatAt;
        long lastBotConversationAt;

        BotProfile(String name, String style) {
            this.name = name;
            this.style = style;
        }

        void remember(String message) {
            history.addLast(message);

            while (history.size() > 18) {
                history.removeFirst();
            }
        }
    }
}
