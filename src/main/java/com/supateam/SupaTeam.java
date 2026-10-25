package com.supateam;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private Component text(String s) { return mm.deserialize(s.replace("&", "§")); }
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

    private void openTeamInfo(Player viewer, Team team) {
        Inventory inv = Bukkit.createInventory(null, 54, text("<dark_gray>Team: " + team.name));
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var fm = filler.getItemMeta(); fm.displayName(text("<dark_gray> ")); filler.setItemMeta(fm);
        for (int i=45;i<54;i++) inv.setItem(i, filler);
        ItemStack info = new ItemStack(Material.IRON_HELMET); var meta = info.getItemMeta();
        meta.displayName(text("<aqua>" + team.name));
        meta.lore(List.of(text("<gray>Owner: <white>" + Optional.ofNullable(Bukkit.getOfflinePlayer(team.owner).getName()).orElse("Unknown")), text("<gray>Members: <white>" + team.members.size() + "/" + maxMembers()), text("<gray>Home: <white>" + (team.home == null ? "Not set" : "Set"))));
        info.setItemMeta(meta); inv.setItem(49, info);
        int slot=0;
        for (UUID id : team.members) {
            if (slot >= 45) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD); SkullMeta skull=(SkullMeta)head.getItemMeta();
            OfflinePlayer member=Bukkit.getOfflinePlayer(id); skull.setOwningPlayer(member);
            String name=Optional.ofNullable(member.getName()).orElse("Unknown");
            skull.displayName(text((id.equals(team.owner) ? "<gold>★ " : team.mods.contains(id) ? "<aqua>◆ " : "<white>") + name));
            skull.lore(List.of(text(id.equals(team.owner) ? "<gold>Team Owner" : team.mods.contains(id) ? "<aqua>Moderator" : "<gray>Member")));
            head.setItemMeta(skull); inv.setItem(slot++, head);
        }
        viewer.openInventory(inv);
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().title().toString().contains("Team: ")) event.setCancelled(true);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { teleporting.remove(event.getPlayer().getUniqueId()); teamChat.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void onChat(AsyncPlayerChatEvent event) {
        Player p=event.getPlayer(); if (!teamChat.contains(p.getUniqueId())) return;
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
        final String name; final UUID owner; final Set<UUID> members=new LinkedHashSet<>(); final Set<UUID> mods=new HashSet<>(); Location home;
        Team(String name,UUID owner){this.name=name;this.owner=owner;}
    }
}
