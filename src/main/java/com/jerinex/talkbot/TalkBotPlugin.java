package com.jerinex.talkbot;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public final class TalkBotPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, BotProfile> bots = new ConcurrentHashMap<>();
    private final Map<String, Long> lastReply = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCombat = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> memory = new ConcurrentHashMap<>();
    private final Set<String> generatedNames = ConcurrentHashMap.newKeySet();
    private final Random random = new Random();

    @Override public void onEnable() {
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("talkbot")).setExecutor(this);
        Objects.requireNonNull(getCommand("talkbot")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getConfig().isConfigurationSection("registered-bots")) {
            for (String key : getConfig().getConfigurationSection("registered-bots").getKeys(false)) {
                String style = getConfig().getString("registered-bots." + key, "chill");
                bots.put(key.toLowerCase(Locale.ROOT), new BotProfile(key, style));
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::maybeBotConversation, 200L, 200L);
        getLogger().info("TalkBot enabled. Register PvpBot names with /talkbot <name>.");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage("§e/talkbot <bot> | /talkbot <bot> style <style> | /talkbot <bot> off | /talkbot list"); return true; }
        if (args[0].equalsIgnoreCase("list")) { sender.sendMessage("§aTalkBots: §f" + (bots.isEmpty() ? "none" : String.join(", ", bots.keySet()))); return true; }
        String name = args[0];
        if (args.length >= 2 && args[1].equalsIgnoreCase("off")) { bots.remove(name.toLowerCase(Locale.ROOT)); getConfig().set("registered-bots." + name, null); saveConfig(); sender.sendMessage("§cTalkBot disabled for " + name); return true; }
        if (args.length >= 3 && args[1].equalsIgnoreCase("style")) {
            BotProfile p = bots.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new BotProfile(name, "chill"));
            String style = args[2].toLowerCase(Locale.ROOT);
            if (!style.matches("dry|sus|toxic|chill|supernice|brainrot|chillbot1|chillbot2")) { sender.sendMessage("§cStyles: dry, sus, toxic, chill, supernice, brainrot, chillbot1, chillbot2"); return true; }
            p.style = style; saveProfiles(); sender.sendMessage("§a" + name + " is now " + style + "."); return true;
        }
        Player bot = Bukkit.getPlayerExact(name);
        if (bot == null) { sender.sendMessage("§cI can't see a PvpBot/player named " + name + " online. Spawn it first, then run this command."); return true; }
        bots.put(name.toLowerCase(Locale.ROOT), new BotProfile(bot.getName(), "chill")); saveProfiles();
        sender.sendMessage("§aTalkBot enabled for " + bot.getName() + " (style: chill). Use /talkbot " + bot.getName() + " style <style>.");
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) { List<String> out = new ArrayList<>(bots.keySet()); out.add("list"); for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); return out; }
        if (args.length == 2) return List.of("style", "off");
        if (args.length == 3 && args[1].equalsIgnoreCase("style")) return List.of("dry", "sus", "toxic", "chill", "supernice", "brainrot", "chillbot1", "chillbot2");
        return List.of();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player speaker = event.getPlayer();
        if (bots.containsKey(speaker.getName().toLowerCase(Locale.ROOT))) return;
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (message.isEmpty() || (getConfig().getBoolean("chat.ignore-commands", true) && message.startsWith("/"))) return;
        remember("server", speaker.getName() + ": " + message);
        for (BotProfile profile : bots.values()) {
            if (random.nextDouble() > getConfig().getDouble("chat.reply-chance", .72)) continue;
            if (!eligible(profile.name, "chat", getConfig().getLong("chat.cooldown-seconds", 8))) continue;
            String context = message;
            ask(profile, "A player named " + speaker.getName() + " said: \"" + context + "\". Decide naturally whether to reply. If irrelevant or you have nothing useful to say, output exactly [SKIP]. Otherwise reply casually in at most " + getConfig().getInt("chat.max-reply-words", 24) + " words. You are playing Minecraft with them.", false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player bot = asBot(event.getEntity());
        if (bot == null) bot = asBot(event.getDamager());
        if (bot == null || !eligible(bot.getName(), "combat", getConfig().getLong("combat.cooldown-seconds", 5))) return;
        String opponent = event.getDamager() instanceof Player p ? p.getName() : event.getEntity() instanceof Player p ? p.getName() : "someone";
        if (random.nextDouble() < getConfig().getDouble("combat.react-chance", .65)) {
            BotProfile profile = bots.get(bot.getName().toLowerCase(Locale.ROOT));
            if (profile != null) ask(profile, "You are in a Minecraft fight. " + opponent + " just hit/was hit. Say a short natural reaction, or [SKIP].", true);
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        Player bot = event.getEntity(); BotProfile p = bots.get(bot.getName().toLowerCase(Locale.ROOT));
        if (p != null && random.nextDouble() < .8) ask(p, "You just died in Minecraft. Say a short in-character death reaction, or [SKIP].", true);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) { /* keep profile so relogged bots retain personality */ }


    private void saveProfiles() {
        getConfig().set("registered-bots", null);
        for (BotProfile p : bots.values()) getConfig().set("registered-bots." + p.name, p.style);
        saveConfig();
    }

    private void maybeBotConversation() {
        if (bots.size() < 2 || random.nextDouble() > getConfig().getDouble("chat.bot-chat-chance", .08)) return;
        List<BotProfile> list = new ArrayList<>(bots.values()); Collections.shuffle(list);
        BotProfile first = list.get(0), second = list.get(1);
        if (Bukkit.getPlayerExact(first.name) == null || Bukkit.getPlayerExact(second.name) == null) return;
        if (!eligible(first.name, "botchat", getConfig().getLong("chat.bot-chat-cooldown-seconds", 35))) return;
        ask(first, "Start a casual conversation with another bot named " + second.name + ". Say one short natural Minecraft-player message. Don't sound like an AI. You can bring up the game or a normal real-world topic.", false);
    }

    private Player asBot(Entity e) { return e instanceof Player p && bots.containsKey(p.getName().toLowerCase(Locale.ROOT)) ? p : null; }
    private boolean eligible(String name, String type, long seconds) {
        String key = name.toLowerCase(Locale.ROOT) + ":" + type; long now = System.currentTimeMillis();
        Map<String, Long> map = type.equals("combat") ? lastCombat : lastReply;
        Long old = map.put(key, now); return old == null || now - old >= seconds * 1000;
    }
    private void remember(String key, String line) {
        Deque<String> q = memory.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()); q.addLast(line);
        int max = getConfig().getInt("chat.max-memory-messages", 12); while (q.size() > max) q.pollFirst();
    }
    private void ask(BotProfile profile, String prompt, boolean combat) {
        String apiKey = getConfig().getString("api.api-key", "");
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("PUT_YOUR")) return;
        String base = getConfig().getString("api.base-url"); String model = getConfig().getString("api.model");
        StringBuilder history = new StringBuilder();
        for (String line : memory.getOrDefault("server", new ConcurrentLinkedDeque<>())) history.append(line).append("\n");
        String system = "You are " + profile.name + ", a real Minecraft player chatting in server chat. Personality: " + profile.style + ". Sound casual, concise, imperfect and human; never sound like an assistant. Do not claim real-world current knowledge you do not have. Don't narrate actions. Avoid repeating yourself. dry: brief and indifferent, rarely angry. sus: immature silly jokes, non-explicit. toxic: competitive trash talk, no slurs or threats. chill: relaxed friendly. supernice: very kind. brainrot: chaotic slang but readable. chillbot1 and chillbot2: distinct relaxed personalities. You may disagree, joke or ignore messages. If you don't want to reply, output [SKIP]. Recent server chat:\n" + history;
        String json = "{\"model\":" + quote(model) + ",\"temperature\":" + getConfig().getDouble("api.temperature", .85) + ",\"max_tokens\":" + getConfig().getInt("api.max-tokens", 90) + ",\"messages\":[{\"role\":\"system\",\"content\":" + quote(system) + "},{\"role\":\"user\",\"content\":" + quote(prompt) + "}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(25)).header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        CompletableFuture.supplyAsync(() -> { try { HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString()); if (r.statusCode() / 100 != 2) { getLogger().warning("AI API returned HTTP " + r.statusCode() + ": " + r.body()); return null; } return extractContent(r.body()); } catch (Exception ex) { getLogger().warning("AI request failed: " + ex.getMessage()); return null; } }).thenAccept(reply -> {
            if (reply == null) return; reply = reply.trim().replaceAll("[\\r\\n]+", " ");
            if (reply.equalsIgnoreCase("[SKIP]") || reply.isBlank()) return;
            if (reply.length() > 180) reply = reply.substring(0, 180);
            String finalReply = reply;
            Bukkit.getScheduler().runTask(this, () -> {
                Player bot = Bukkit.getPlayerExact(profile.name); if (bot == null || !bots.containsKey(profile.name.toLowerCase(Locale.ROOT))) return;
                // PvpBot bots are player entities. Player.chat() sends through the normal server chat pipeline.
                generatedNames.add(profile.name.toLowerCase(Locale.ROOT));
                try { bot.chat(finalReply); } catch (Throwable ex) { getLogger().warning("Could not make " + profile.name + " chat: " + ex.getMessage()); }
                remember("server", profile.name + ": " + finalReply);
            });
        });
    }
    private static String quote(String s) { if (s == null) s = ""; return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private static String extractContent(String json) {
        Matcher m = Pattern.compile("\\\"content\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").matcher(json);
        if (!m.find()) return null; String s = m.group(1);
        return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"").replace("\\\\", "\\");
    }
    private static final class BotProfile { final String name; volatile String style; BotProfile(String name, String style) { this.name = name; this.style = style; } }
}
