package com.supateam;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SupaTeam extends JavaPlugin implements Listener, TabExecutor {
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<UUID, String> membership = new HashMap<>();
    private final Map<UUID, String> invites = new HashMap<>();
    private final Set<UUID> teamChat = ConcurrentHashMap.newKeySet();
    private final Set<UUID> teleporting = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> disbandRequests = new HashMap<>();
    private final Map<UUID, Integer> guiPages = new HashMap<>();
    private final Map<UUID, String> guiSearch = new HashMap<>();
    private final Map<UUID, String> guiSort = new HashMap<>();
    private final Set<UUID> awaitingSearch = ConcurrentHashMap.newKeySet();
    private final Set<UUID> awaitingInvite = ConcurrentHashMap.newKeySet();
    private final Set<UUID> handledGuiChat = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> selectedMembers = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private final MiniMessage mm = MiniMessage.miniMessage();

    @Override public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "teams.yml");
        loadTeams();
        Objects.requireNonNull(getCommand("team")).setExecutor(this);
        Objects.requireNonNull(getCommand("team")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new TeamPlaceholders().register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }
        getLogger().info("SupaTeam enabled. Loaded " + teams.size() + " teams.");
    }

    @Override public void onDisable() { saveTeams(); }
    private Component text(String s) { return mm.deserialize(s).decoration(TextDecoration.ITALIC, false); }
    private void msg(CommandSender sender, String s) { sender.sendMessage(text(s)); }
    private int maxMembers() { return Math.max(2, getConfig().getInt("teams.max-members", 14)); }
    private Team teamOf(UUID id) { String name = membership.get(id); return name == null ? null : teams.get(key(name)); }
    private String key(String s) { return s.toLowerCase(Locale.ROOT); }
    private boolean isOwner(Player p, Team t) { return t.owner.equals(p.getUniqueId()); }
    private String displayName(Team t) { return t.name; }
    private void saveTeams() {
        data = new YamlConfiguration();
        for (Team t : teams.values()) {
            String root = "teams." + t.name;
            data.set(root + ".owner", t.owner.toString());
            List<String> members = new ArrayList<>(), mods = new ArrayList<>();
            for (UUID id : t.members) members.add(id.toString());
            for (UUID id : t.mods) mods.add(id.toString());
            data.set(root + ".members", members);
            data.set(root + ".moderators", mods);
            data.set(root + ".pvp", t.pvp);
            if (t.home != null) {
                data.set(root + ".home.world", t.home.getWorld().getName());
                data.set(root + ".home.x", t.home.getX()); data.set(root + ".home.y", t.home.getY()); data.set(root + ".home.z", t.home.getZ());
                data.set(root + ".home.yaw", t.home.getYaw()); data.set(root + ".home.pitch", t.home.getPitch());
            }
        }
        try { data.save(dataFile); } catch (IOException e) { getLogger().severe("Could not save teams.yml: " + e.getMessage()); }
    }
    private void loadTeams() {
        if (!dataFile.exists()) { try { getDataFolder().mkdirs(); dataFile.createNewFile(); } catch (IOException e) { getLogger().warning(e.getMessage()); } }
        data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = data.getConfigurationSection("teams");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            String ownerText = data.getString("teams." + name + ".owner");
            if (ownerText == null) continue;
            try {
                Team t = new Team(name, UUID.fromString(ownerText));
                for (String id : data.getStringList("teams." + name + ".members")) try { t.members.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
                for (String id : data.getStringList("teams." + name + ".moderators")) try { t.mods.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
                t.pvp = data.getBoolean("teams." + name + ".pvp", false);
                if (!t.members.contains(t.owner)) t.members.add(t.owner);
                String worldName = data.getString("teams." + name + ".home.world");
                World world = worldName == null ? null : Bukkit.getWorld(worldName);
                if (world != null) t.home = new Location(world, data.getDouble("teams." + name + ".home.x"), data.getDouble("teams." + name + ".home.y"), data.getDouble("teams." + name + ".home.z"), (float)data.getDouble("teams." + name + ".home.yaw"), (float)data.getDouble("teams." + name + ".home.pitch"));
                teams.put(key(name), t);
                for (UUID id : t.members) membership.put(id, key(name));
            } catch (IllegalArgumentException e) { getLogger().warning("Skipping team with invalid owner UUID: " + name); }
        }
    }
    private void broadcast(Team t, String message) { for (UUID id : t.members) { Player p = Bukkit.getPlayer(id); if (p != null) msg(p, message); } }
    private boolean playerOnly(CommandSender sender) { if (sender instanceof Player) return true; msg(sender, "<red>This command can only be used in-game.</red>"); return false; }
    private boolean fail(Player p, String message) { msg(p, message); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1); return true; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!playerOnly(sender)) return true;
        Player p = (Player)sender;
        if (!p.hasPermission("supateam.use")) return fail(p, "<red>You do not have permission.</red>");
        if (args.length == 0) {
            Team t = teamOf(p.getUniqueId());
            if (t == null) return fail(p, "<red>You don't have a team. Type /team create <name> to create one.</red>");
            openTeamInfo(p, t); return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        Team team = teamOf(p.getUniqueId());
        switch (sub) {
            case "create" -> {
                if (args.length < 2) return fail(p, "<red>Usage: /team create <name></red>");
                if (team != null) return fail(p, "<red>You are already in a team.</red>");
                String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                if (!name.matches("[A-Za-z0-9_ -]{2,24}")) return fail(p, "<red>Team names must be 2–24 characters and use letters, numbers, spaces, _ or -.</red>");
                if (teams.containsKey(key(name))) return fail(p, "<red>A team with that name already exists.</red>");
                Team t = new Team(name, p.getUniqueId()); t.members.add(p.getUniqueId()); teams.put(key(name), t); membership.put(p.getUniqueId(), key(name)); saveTeams();
                msg(p, "<green>Team <aqua>" + name + "</aqua> created.</green>"); p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1); return true;
            }
            case "join" -> {
                if (args.length < 2) return fail(p, "<red>Usage: /team join <name></red>");
                if (team != null) return fail(p, "<red>You are already in a team.</red>");
                String invited = invites.get(p.getUniqueId());
                Team target = teams.get(key(String.join(" ", Arrays.copyOfRange(args, 1, args.length))));
                if (target == null) return fail(p, "<red>That team does not exist.</red>");
                if (invited == null || !invited.equals(key(target.name))) return fail(p, "<red>You are not invited to that team.</red>");
                if (target.members.size() >= maxMembers()) return fail(p, "<red>That team is full.</red>");
                target.members.add(p.getUniqueId()); membership.put(p.getUniqueId(), key(target.name)); invites.remove(p.getUniqueId()); saveTeams();
                broadcast(target, "<aqua>" + p.getName() + "</aqua> <gray>joined the team.</gray>"); return true;
            }
            case "invite" -> {
                if (args.length < 2) return fail(p, "<red>Usage: /team invite <player></red>");
                if (team == null) return fail(p, "<red>You do not have a team.</red>");
                if (!isOwner(p, team) && !team.mods.contains(p.getUniqueId())) return fail(p, "<red>Only the owner or a moderator can invite players.</red>");
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) return fail(p, "<red>That player is not online.</red>");
                if (teamOf(target.getUniqueId()) != null) return fail(p, "<red>That player is already in a team.</red>");
                if (target.equals(p)) return fail(p, "<red>You cannot invite yourself.</red>");
                if (team.members.size() >= maxMembers()) return fail(p, "<red>Your team is full.</red>");
                invites.put(target.getUniqueId(), key(team.name));
                msg(p, "<gray>Invited <aqua>" + target.getName() + "</aqua> to your team.</gray>");
                msg(target, "<aqua>" + p.getName() + "</aqua> <gray>invited you to <aqua>" + team.name + "</aqua>. Use <click:run_command:'/team join " + team.name.replace("'", "") + "'><green>[CLICK TO JOIN]</green></click> or /team join " + team.name + ".");
                long expiry = getConfig().getLong("teams.invite-expiry-seconds", 60);
                Bukkit.getScheduler().runTaskLater(this, () -> { if (key(team.name).equals(invites.get(target.getUniqueId()))) invites.remove(target.getUniqueId()); }, Math.max(1, expiry) * 20L);
                return true;
            }
            case "leave" -> {
                if (team == null) return fail(p, "<red>You do not have a team.</red>");
                if (isOwner(p, team)) return fail(p, "<red>The owner cannot leave. Use /team disband instead.</red>");
                team.members.remove(p.getUniqueId()); team.mods.remove(p.getUniqueId()); membership.remove(p.getUniqueId()); teamChat.remove(p.getUniqueId()); saveTeams(); broadcast(team, "<aqua>" + p.getName() + "</aqua> <gray>left the team.</gray>"); msg(p, "<green>You left the team.</green>"); return true;
            }
            case "info" -> {
                Team target = args.length >= 2 ? teams.get(key(String.join(" ", Arrays.copyOfRange(args, 1, args.length)))) : team;
                if (target == null) return fail(p, "<red>Team not found. Usage: /team info [name]</red>");
                openTeamInfo(p, target); return true;
            }
            case "sethome" -> {
                if (team == null) return fail(p, "<red>You do not have a team.</red>");
                if (!isOwner(p, team)) return fail(p, "<red>Only your team owner can set the home.</red>");
                if (getConfig().getStringList("teams.blocked-home-worlds").contains(p.getWorld().getName())) return fail(p, "<red>You cannot set a team home in this world.</red>");
                team.home = p.getLocation().clone(); saveTeams(); msg(p, "<green>Team home set.</green>"); return true;
            }
            case "delhome" -> {
                if (team == null) return fail(p, "<red>You do not have a team.</red>");
                if (!isOwner(p, team)) return fail(p, "<red>Only your team owner can delete the home.</red>");
                if (team.home == null) return fail(p, "<gray>Your team does not have a home.</gray>");
                team.home = null; saveTeams(); msg(p, "<green>Team home deleted.</green>"); return true;
            }
            case "home" -> {
                if (team == null) return fail(p, "<red>You do not have a team.</red>");
                if (team.home == null || team.home.getWorld() == null) return fail(p, "<red>Your team does not have a home.</red>");
                if (!teleporting.add(p.getUniqueId())) return true;
                Location start = p.getLocation().clone(); int delay = Math.max(0, getConfig().getInt("teams.home-teleport-delay-seconds", 5));
                msg(p, "<gray>Teleporting to team home in <aqua>" + delay + "</aqua> seconds. Don't move.</gray>");
                new BukkitRunnable() { int left = delay; public void run() {
                    if (!p.isOnline() || !teleporting.contains(p.getUniqueId())) { cancel(); return; }
                    if (p.getLocation().distanceSquared(start) > 0.25) { teleporting.remove(p.getUniqueId()); msg(p, "<red>Teleport cancelled because you moved.</red>"); cancel(); return; }
                    if (left-- <= 0) { p.teleport(team.home); teleporting.remove(p.getUniqueId()); msg(p, "<green>Teleported to team home.</green>"); cancel(); }
                }}.runTaskTimer(this, 0L, 20L); return true;
            }
            case "kick" -> {
                if (args.length < 2) return fail(p, "<red>Usage: /team kick <player></red>");
                if (team == null || (!isOwner(p, team) && !team.mods.contains(p.getUniqueId()))) return fail(p, "<red>Only the owner or a moderator can kick players.</red>");
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]); UUID id = target.getUniqueId();
                if (!team.members.contains(id)) return fail(p, "<red>That player is not in your team.</red>");
                if (id.equals(team.owner)) return fail(p, "<red>You cannot kick the team owner.</red>");
                if (!isOwner(p, team) && team.mods.contains(id)) return fail(p, "<red>Moderators cannot kick other moderators.</red>");
                team.members.remove(id); team.mods.remove(id); membership.remove(id); teamChat.remove(id); saveTeams(); broadcast(team, "<red>" + target.getName() + " was kicked from the team.</red>");
                if (Bukkit.getPlayer(id) != null) msg(Bukkit.getPlayer(id), "<red>You were kicked from the team.</red>"); return true;
            }
            case "disband" -> {
                if (team == null || !isOwner(p, team)) return fail(p, "<red>Only a team owner can disband a team.</red>");
                if (args.length == 1) { openDisbandConfirm(p); return true; }
                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    disbandRequests.put(p.getUniqueId(), System.currentTimeMillis() + 15000L);
                    msg(p, "<red>This permanently deletes <white>" + team.name + "</white>. Type <yellow>/team disband confirm</yellow> within 15 seconds.</red>"); return true;
                }
                Long expiry = disbandRequests.get(p.getUniqueId());
                if (expiry == null || expiry < System.currentTimeMillis()) { disbandRequests.remove(p.getUniqueId()); return fail(p, "<red>Confirmation expired. Run /team disband again.</red>"); }
                for (UUID id : team.members) { membership.remove(id); teamChat.remove(id); if (Bukkit.getPlayer(id) != null) msg(Bukkit.getPlayer(id), "<red>Your team was disbanded.</red>"); }
                teams.remove(key(team.name)); disbandRequests.remove(p.getUniqueId()); saveTeams(); return true;
            }
            case "chat" -> {
                if (team == null) return fail(p, "<red>You do not have a team.</red>");
                if (args.length >= 2) { String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length)); broadcast(team, "<aqua>[TEAM] " + p.getName() + ": <white>" + message); }
                else if (teamChat.remove(p.getUniqueId())) msg(p, "<gray>Team chat disabled.</gray>");
                else { teamChat.add(p.getUniqueId()); msg(p, "<gray>Team chat enabled. Your messages will only go to teammates.</gray>"); }
                return true;
            }
            default -> { msg(p, "<aqua>SupaTeam commands:</aqua> <gray>/team create <name>, invite <player>, join <name>, leave, info [name], sethome, delhome, home, kick <player>, chat, disband</gray>"); return true; }
        }
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(text(name));
        meta.lore(lore.stream().map(this::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private void openTeamInfo(Player viewer, Team team) {
        UUID viewerId = viewer.getUniqueId();
        int page = Math.max(0, guiPages.getOrDefault(viewerId, 0));
        String search = guiSearch.getOrDefault(viewerId, "");
        String sort = guiSort.getOrDefault(viewerId, "role");
        Inventory inv = Bukkit.createInventory(null, 54, text("ᴛᴇᴀᴍ | " + team.name + " | " + (page + 1)));
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        ItemStack info = button(Material.NETHER_STAR, "ᴛᴇᴀᴍ ɪɴꜰᴏ", List.of(
                "<gray>Name: <white>" + team.name,
                "<gray>Owner: <white>" + Optional.ofNullable(Bukkit.getOfflinePlayer(team.owner).getName()).orElse("Unknown"),
                "<gray>Members: <white>" + team.members.size() + "/" + maxMembers(),
                "<gray>Home: <white>" + (team.home == null ? "Not set" : "Set"),
                "<gray>Team PvP: " + (team.pvp ? "<green>Enabled" : "<red>Disabled")
        ));
        inv.setItem(49, info);
        inv.setItem(45, button(Material.ARROW, "ᴘʀᴇᴠɪᴏᴜꜱ ᴘᴀɢᴇ", List.of("<gray>Previous member page")));
        inv.setItem(46, button(Material.COMPASS, "ꜱᴇᴀʀᴄʜ ᴍᴇᴍʙᴇʀꜱ", List.of("<gray>Filter: " + (search.isEmpty() ? "None" : search), "<yellow>Click and type in chat", "<gray>Type clear to reset")));
        inv.setItem(47, button(Material.HOPPER, "ꜱᴏʀᴛ: " + sort.toUpperCase(Locale.ROOT), List.of("<gray>Click to cycle Role / Name / Online")));
        inv.setItem(48, button(Material.CLOCK, "ʀᴇꜰʀᴇꜱʜ", List.of("<gray>Refresh this menu")));
        inv.setItem(50, button(Material.IRON_SWORD, "ᴛᴇᴀᴍ ᴘᴠᴘ", List.of("<gray>Status: " + (team.pvp ? "Enabled" : "Disabled"), "<yellow>Owner can toggle this setting")));
        inv.setItem(51, button(Material.PLAYER_HEAD, "ɪɴᴠɪᴛᴇ ᴘʟᴀʏᴇʀ", List.of("<gray>Click and type an online player name")));
        inv.setItem(52, button(Material.BARRIER, "ᴅɪꜱʙᴀɴᴅ ᴛᴇᴀᴍ", List.of("<red>Owner only", "<gray>Open confirmation menu")));
        inv.setItem(53, button(Material.ARROW, "ɴᴇxᴛ ᴘᴀɢᴇ", List.of("<gray>Next member page")));

        List<UUID> members = team.members.stream().filter(id -> {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("Unknown");
            return name.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
        }).sorted((a, b) -> {
            String na = Optional.ofNullable(Bukkit.getOfflinePlayer(a).getName()).orElse("Unknown");
            String nb = Optional.ofNullable(Bukkit.getOfflinePlayer(b).getName()).orElse("Unknown");
            if (sort.equals("name")) return na.compareToIgnoreCase(nb);
            if (sort.equals("online")) return Boolean.compare(Bukkit.getPlayer(b) != null, Bukkit.getPlayer(a) != null);
            int ra = a.equals(team.owner) ? 0 : team.mods.contains(a) ? 1 : 2;
            int rb = b.equals(team.owner) ? 0 : team.mods.contains(b) ? 1 : 2;
            int role = Integer.compare(ra, rb);
            return role != 0 ? role : na.compareToIgnoreCase(nb);
        }).toList();
        int maxPage = Math.max(0, (members.size() - 1) / 45);
        page = Math.min(page, maxPage);
        guiPages.put(viewerId, page);
        int slot = 0;
        for (UUID id : members.stream().skip((long) page * 45).limit(45).toList()) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skull = (SkullMeta) head.getItemMeta();
            OfflinePlayer member = Bukkit.getOfflinePlayer(id);
            skull.setOwningPlayer(member);
            String name = Optional.ofNullable(member.getName()).orElse("Unknown");
            String role = id.equals(team.owner) ? "<gold>★ " : team.mods.contains(id) ? "<aqua>◆ " : "<white>";
            skull.displayName(text(role + name));
            skull.lore(List.of(
                    text(id.equals(team.owner) ? "<gold>ᴛᴇᴀᴍ ᴏᴡɴᴇʀ" : team.mods.contains(id) ? "<aqua>ᴍᴏᴅᴇʀᴀᴛᴏʀ" : "<gray>ᴍᴇᴍʙᴇʀ"),
                    text(Bukkit.getPlayer(id) == null ? "<red>Offline" : "<green>Online"),
                    text("<yellow>Click to edit member")
            ));
            head.setItemMeta(skull);
            inv.setItem(slot++, head);
        }
        viewer.openInventory(inv);
    }

    private void openMemberEdit(Player viewer, Team team, UUID targetId) {
        selectedMembers.put(viewer.getUniqueId(), targetId);
        Inventory inv = Bukkit.createInventory(null, 27, text("ᴇᴅɪᴛ ᴍᴇᴍʙᴇʀ"));
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        inv.setItem(4, button(Material.PLAYER_HEAD, Optional.ofNullable(target.getName()).orElse("Unknown"), List.of("<gray>Selected team member")));
        if (!targetId.equals(team.owner)) {
            inv.setItem(11, button(team.mods.contains(targetId) ? Material.IRON_INGOT : Material.GOLD_INGOT,
                    team.mods.contains(targetId) ? "ʀᴇᴍᴏᴠᴇ ᴍᴏᴅᴇʀᴀᴛᴏʀ" : "ᴘʀᴏᴍᴏᴛᴇ ᴛᴏ ᴍᴏᴅ",
                    List.of("<gray>Only the owner can change moderator roles")));
            inv.setItem(15, button(Material.BARRIER, "ᴋɪᴄᴋ ᴍᴇᴍʙᴇʀ", List.of("<red>Remove this member from the team")));
        }
        inv.setItem(22, button(Material.ARROW, "ʙᴀᴄᴋ ᴛᴏ ᴛᴇᴀᴍ", List.of("<gray>Return to member list")));
        viewer.openInventory(inv);
    }

    private void openDisbandConfirm(Player viewer) {
        Inventory inv = Bukkit.createInventory(null, 27, text("ᴄᴏɴꜰɪʀᴍ ᴅɪꜱʙᴀɴᴅɪɴɢ ᴛᴇᴀᴍ"));
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        inv.setItem(11, button(Material.LIME_CONCRETE, "ᴄᴏɴꜰɪʀᴍ", List.of("<red>Permanently delete this team")));
        inv.setItem(15, button(Material.RED_CONCRETE, "ᴄᴀɴᴄᴇʟ", List.of("<gray>Keep the team")));
        viewer.openInventory(inv);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.startsWith("ᴛᴇᴀᴍ | ") && !title.equals("ᴇᴅɪᴛ ᴍᴇᴍʙᴇʀ") && !title.equals("ᴄᴏɴꜰɪʀᴍ ᴅɪꜱʙᴀɴᴅɪɴɢ ᴛᴇᴀᴍ")) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        Team team = teamOf(p.getUniqueId());
        int slot = event.getRawSlot();
        if (title.startsWith("ᴛᴇᴀᴍ | ")) {
            if (team == null) { p.closeInventory(); return; }
            if (slot == 45) guiPages.put(p.getUniqueId(), Math.max(0, guiPages.getOrDefault(p.getUniqueId(), 0) - 1));
            else if (slot == 53) guiPages.put(p.getUniqueId(), guiPages.getOrDefault(p.getUniqueId(), 0) + 1);
            else if (slot == 46) { awaitingSearch.add(p.getUniqueId()); p.closeInventory(); msg(p, "<yellow>Type a member name filter in chat, or type clear to reset it.</yellow>"); return; }
            else if (slot == 47) { String sort = guiSort.getOrDefault(p.getUniqueId(), "role"); guiSort.put(p.getUniqueId(), sort.equals("role") ? "name" : sort.equals("name") ? "online" : "role"); }
            else if (slot == 50) { if (isOwner(p, team)) { team.pvp = !team.pvp; saveTeams(); } else msg(p, "<red>Only the owner can toggle team PvP.</red>"); }
            else if (slot == 51) { if (isOwner(p, team) || team.mods.contains(p.getUniqueId())) { awaitingInvite.add(p.getUniqueId()); p.closeInventory(); msg(p, "<yellow>Type the online player's name in chat to invite them.</yellow>"); return; } else msg(p, "<red>Only the owner or a moderator can invite players.</red>"); }
            else if (slot == 52) { if (isOwner(p, team)) { openDisbandConfirm(p); return; } else msg(p, "<red>Only the owner can disband the team.</red>"); }
            else if (slot >= 0 && slot < 45 && (isOwner(p, team) || team.mods.contains(p.getUniqueId()))) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && clicked.getType() == Material.PLAYER_HEAD) {
                    String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(clicked.getItemMeta().displayName());
                    if (plain.startsWith("★ ") || plain.startsWith("◆ ")) plain = plain.substring(2);
                    for (UUID id : team.members) {
                        String name = Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("Unknown");
                        if (name.equals(plain)) { openMemberEdit(p, team, id); return; }
                    }
                }
            }
            openTeamInfo(p, team);
            return;
        }
        if (title.equals("ᴇᴅɪᴛ ᴍᴇᴍʙᴇʀ")) {
            if (team == null) { p.closeInventory(); return; }
            UUID target = selectedMembers.get(p.getUniqueId());
            if (target == null || !team.members.contains(target)) { openTeamInfo(p, team); return; }
            if (slot == 22) { openTeamInfo(p, team); return; }
            if (slot == 11 && isOwner(p, team) && !target.equals(team.owner)) {
                if (!team.mods.add(target)) team.mods.remove(target);
                saveTeams(); openMemberEdit(p, team, target); return;
            }
            if (slot == 15 && !target.equals(team.owner) && (isOwner(p, team) || (!team.mods.contains(target) && team.mods.contains(p.getUniqueId())))) {
                team.members.remove(target); team.mods.remove(target); membership.remove(target); teamChat.remove(target); saveTeams();
                Player online = Bukkit.getPlayer(target); if (online != null) msg(online, "<red>You were kicked from the team.</red>");
                selectedMembers.remove(p.getUniqueId()); openTeamInfo(p, team); return;
            }
            return;
        }
        if (title.equals("ᴄᴏɴꜰɪʀᴍ ᴅɪꜱʙᴀɴᴅɪɴɢ ᴛᴇᴀᴍ")) {
            if (team == null || !isOwner(p, team)) { p.closeInventory(); return; }
            if (slot == 11) {
                for (UUID id : team.members) { membership.remove(id); teamChat.remove(id); Player online = Bukkit.getPlayer(id); if (online != null) msg(online, "<red>Your team was disbanded.</red>"); }
                teams.remove(key(team.name)); saveTeams(); p.closeInventory(); return;
            }
            if (slot == 15) p.closeInventory();
        }
    }

    @EventHandler public void onGuiChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        if (awaitingSearch.remove(p.getUniqueId())) {
            handledGuiChat.add(p.getUniqueId());
            event.setCancelled(true);
            String filter = event.getMessage().trim();
            Bukkit.getScheduler().runTask(this, () -> {
                guiSearch.put(p.getUniqueId(), filter.equalsIgnoreCase("clear") ? "" : filter);
                Team team = teamOf(p.getUniqueId());
                if (team != null) openTeamInfo(p, team);
            });
            return;
        }
        if (awaitingInvite.remove(p.getUniqueId())) {
            handledGuiChat.add(p.getUniqueId());
            event.setCancelled(true);
            String targetName = event.getMessage().trim();
            Bukkit.getScheduler().runTask(this, () -> {
                Team team = teamOf(p.getUniqueId());
                Player target = Bukkit.getPlayerExact(targetName);
                if (team == null) { msg(p, "<red>You are no longer in a team.</red>"); return; }
                if (target == null || target.equals(p) || teamOf(target.getUniqueId()) != null || team.members.size() >= maxMembers()) {
                    msg(p, "<red>Cannot invite that player. Check the name, membership, and team capacity.</red>");
                    openTeamInfo(p, team);
                    return;
                }
                invites.put(target.getUniqueId(), key(team.name));
                msg(p, "<green>Invite sent to <aqua>" + target.getName() + "</aqua>.</green>");
                msg(target, "<aqua>" + p.getName() + "</aqua> <gray>invited you to <aqua>" + team.name + "</aqua>. Use /team join " + team.name + ".");
                long expiry = Math.max(1, getConfig().getLong("teams.invite-expiry-seconds", 60));
                Bukkit.getScheduler().runTaskLater(this, () -> { if (key(team.name).equals(invites.get(target.getUniqueId()))) invites.remove(target.getUniqueId()); }, expiry * 20L);
                openTeamInfo(p, team);
            });
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { UUID id=event.getPlayer().getUniqueId(); teleporting.remove(id); teamChat.remove(id); awaitingSearch.remove(id); awaitingInvite.remove(id); handledGuiChat.remove(id); selectedMembers.remove(id); }
    @EventHandler public void onFriendlyFire(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = event.getDamager() instanceof Player direct ? direct : event.getDamager() instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
        if (attacker == null) return;
        Team victimTeam=teamOf(victim.getUniqueId()); Team attackerTeam=teamOf(attacker.getUniqueId());
        if (victimTeam != null && victimTeam == attackerTeam && !victimTeam.pvp) event.setCancelled(true);
    }
    @EventHandler public void onChat(AsyncPlayerChatEvent event) {
        Player p=event.getPlayer(); if (handledGuiChat.remove(p.getUniqueId()) || awaitingSearch.contains(p.getUniqueId()) || awaitingInvite.contains(p.getUniqueId())) return; if (!teamChat.contains(p.getUniqueId())) return;
        Team t=teamOf(p.getUniqueId()); if (t == null) { teamChat.remove(p.getUniqueId()); return; }
        event.setCancelled(true); String message=event.getMessage();
        Bukkit.getScheduler().runTask(this, () -> broadcast(t, "<aqua>[TEAM] " + p.getName() + ": <white>" + message));
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player p)) return Collections.emptyList();
        Team t=teamOf(p.getUniqueId()); List<String> options=new ArrayList<>();
        if (args.length==1) {
            if (t==null) options.addAll(List.of("create","join","info"));
            else options.addAll(List.of("leave","delhome","chat","invite","disband","info","kick","sethome","home"));
        } else if (args.length==2) {
            switch(args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> options.add("<name>");
                case "join","info" -> options.addAll(teams.values().stream().map(x->x.name).toList());
                case "invite" -> options.addAll(Bukkit.getOnlinePlayers().stream().filter(x->teamOf(x.getUniqueId())==null).map(Player::getName).toList());
                case "kick" -> { if(t!=null && (isOwner(p,t)||t.mods.contains(p.getUniqueId()))) for(UUID id:t.members) if(!id.equals(t.owner)) options.add(Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("")); }
                case "disband" -> options.add("confirm");
            }
        }
        String prefix=args[args.length-1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    private final class TeamPlaceholders extends PlaceholderExpansion {
        @Override public String getIdentifier(){return "team";}
        @Override public String getAuthor(){return "univer404";}
        @Override public String getVersion(){return getPluginMeta().getVersion();}
        @Override public boolean persist(){return true;}
        @Override public String onRequest(OfflinePlayer player,String params){
            if(player==null) return "";
            Team t=teamOf(player.getUniqueId());
            return switch(params.toLowerCase(Locale.ROOT)){case "name"->t==null?"":t.name; case "inteam"->Boolean.toString(t!=null); case "members"->t==null?"0":Integer.toString(t.members.size()); default->null;};
        }
    }
    private static final class Team {
        final String name; final UUID owner; final Set<UUID> members=new LinkedHashSet<>(); final Set<UUID> mods=new HashSet<>(); Location home; boolean pvp=false;
        Team(String name,UUID owner){this.name=name;this.owner=owner;}
    }
}
