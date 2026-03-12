package saki4.skpsblock;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.*;

public class SKpsblock extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, Long> deleteConfirms = new HashMap<>();
    private final Map<UUID, String> lastRegions = new HashMap<>();
    private Connection connection;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initDatabase();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("ps").setExecutor(this);
    }

    @Override
    public void onDisable() {
        try { if (connection != null && !connection.isClosed()) connection.close(); } catch (SQLException e) { e.printStackTrace(); }
    }

    private void initDatabase() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/regions.db");
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS regions (region_name TEXT PRIMARY KEY, owner_uuid TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER, hp INTEGER)");
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private String msg(String path) {
        String s = getConfig().getString("messages." + path);
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix") + s);
    }

    private boolean hasP(Player p, String action) {
        return p.hasPermission(getConfig().getString("permissions." + action));
    }

    // --- Система HP и Взрывов ---
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Location loc = event.getLocation();
        RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(loc.getWorld()));
        if (rm == null) return;

        ApplicableRegionSet set = rm.getApplicableRegions(BukkitAdapter.asBlockVector(loc));
        for (ProtectedRegion r : set) {
            if (r.getId().startsWith("sk-")) {
                event.setCancelled(true);
                damageRegion(r, loc.getWorld());
                break;
            }
        }
    }

    private void damageRegion(ProtectedRegion r, World world) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT hp, x, y, z FROM regions WHERE region_name = ?")) {
            ps.setString(1, r.getId());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                int hp = rs.getInt("hp") - 1;
                int x = rs.getInt("x"), y = rs.getInt("y"), z = rs.getInt("z");

                if (hp <= 0) {
                    RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
                    rm.removeRegion(r.getId());
                    removeRegionFromDB(r.getId());
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                    notifyOwner(r, msg("region_destroyed"));
                } else {
                    try (PreparedStatement up = connection.prepareStatement("UPDATE regions SET hp = ? WHERE region_name = ?")) {
                        up.setInt(1, hp);
                        up.setString(2, r.getId());
                        up.executeUpdate();
                    }
                    int maxHp = getConfig().getInt("blocks.IRON_BLOCK.hp", 3);
                    notifyOwner(r, msg("region_damaged").replace("{hp}", String.valueOf(hp)).replace("{max}", String.valueOf(maxHp)));
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void notifyOwner(ProtectedRegion r, String message) {
        r.getOwners().getUniqueIds().forEach(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(message);
        });
    }

    // --- Установка и Удаление ---
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        Location loc = b.getLocation();
        try (PreparedStatement ps = connection.prepareStatement("SELECT region_name, owner_uuid FROM regions WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            ps.setString(1, loc.getWorld().getName());
            ps.setInt(2, loc.getBlockX());
            ps.setInt(3, loc.getBlockY());
            ps.setInt(4, loc.getBlockZ());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String regionName = rs.getString("region_name");
                UUID ownerUUID = UUID.fromString(rs.getString("owner_uuid"));
                Player p = event.getPlayer();
                if (p.getUniqueId().equals(ownerUUID) || p.isOp()) {
                    RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(loc.getWorld()));
                    if (rm != null) rm.removeRegion(regionName);
                    removeRegionFromDB(regionName);
                    event.setDropItems(false);
                    loc.getWorld().dropItemNaturally(loc, new ItemStack(b.getType()));
                    p.sendMessage(msg("region_break"));
                } else {
                    p.sendMessage(msg("not_owner_break"));
                    event.setCancelled(true);
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        Block b = event.getBlock();
        String mat = b.getType().name();
        if (getConfig().contains("blocks." + mat)) {
            int max = 0;
            for (int i = 1; i <= 100; i++) if (p.hasPermission("limit_ps_" + i)) max = Math.max(max, i);
            RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(p.getWorld()));
            if (rm == null) return;
            long count = rm.getRegions().values().stream().filter(r -> r.getOwners().contains(p.getUniqueId())).count();
            if (count >= max && !p.isOp()) {
                p.sendMessage(msg("limit_reached"));
                event.setCancelled(true);
                return;
            }
            int radius = getConfig().getInt("blocks." + mat + ".size") / 2;
            int hp = getConfig().getInt("blocks." + mat + ".hp", 3);
            Location loc = b.getLocation();
            BlockVector3 min = BlockVector3.at(loc.getBlockX() - radius, loc.getBlockY() - radius, loc.getBlockZ() - radius);
            BlockVector3 maxV = BlockVector3.at(loc.getBlockX() + radius, loc.getBlockY() + radius, loc.getBlockZ() + radius);
            String id = "sk-" + (1000000 + new Random().nextInt(9000000));
            ProtectedRegion rg = new ProtectedCuboidRegion(id, min, maxV);
            if (rm.getApplicableRegions(rg).size() > 0) {
                p.sendMessage(msg("region_overlap"));
                event.setCancelled(true);
                return;
            }
            rg.getOwners().addPlayer(p.getUniqueId());
            rm.addRegion(rg);
            saveRegionToDB(id, p.getUniqueId(), loc, hp);
            p.sendMessage(msg("region_create").replace("{hp}", String.valueOf(hp)));
        }
    }

    private void saveRegionToDB(String name, UUID owner, Location loc, int hp) {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO regions VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, name); ps.setString(2, owner.toString());
            ps.setString(3, loc.getWorld().getName()); ps.setInt(4, loc.getBlockX());
            ps.setInt(5, loc.getBlockY()); ps.setInt(6, loc.getBlockZ());
            ps.setInt(7, hp);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void removeRegionFromDB(String name) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM regions WHERE region_name = ?")) {
            ps.setString(1, name); ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            if (!hasP(p, "help")) { p.sendMessage(msg("no_permission")); return true; }
            getConfig().getStringList("messages.help").forEach(line -> p.sendMessage(ChatColor.translateAlternateColorCodes('&', line)));
            return true;
        }

        RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(p.getWorld()));
        if (rm == null) return true;

        if (args[0].equalsIgnoreCase("info")) {
            if (!hasP(p, "info")) { p.sendMessage(msg("no_permission")); return true; }
            ApplicableRegionSet set = rm.getApplicableRegions(BukkitAdapter.asBlockVector(p.getLocation()));
            ProtectedRegion r = set.getRegions().stream().filter(rg -> rg.getId().startsWith("sk-")).findFirst().orElse(null);
            if (r != null) {
                try (PreparedStatement ps = connection.prepareStatement("SELECT hp FROM regions WHERE region_name = ?")) {
                    ps.setString(1, r.getId());
                    ResultSet rs = ps.executeQuery();
                    int hp = rs.next() ? rs.getInt("hp") : 0;
                    String owner = Bukkit.getOfflinePlayer(r.getOwners().getUniqueIds().iterator().next()).getName();

                    StringJoiner sj = new StringJoiner(", ");
                    r.getMembers().getUniqueIds().forEach(u -> {
                        String n = Bukkit.getOfflinePlayer(u).getName();
                        if (n != null) sj.add(n);
                    });

                    int x = r.getMaximumPoint().getX() - r.getMinimumPoint().getX() + 1;
                    String info = getConfig().getString("messages.info_format")
                            .replace("{owner}", owner != null ? owner : "Unknown")
                            .replace("{members}", sj.toString().isEmpty() ? "" : sj.toString())
                            .replace("{size}", x + "x" + x + "x" + x)
                            .replace("{hp}", String.valueOf(hp))
                            .replace("{max}", String.valueOf(getConfig().getInt("blocks.IRON_BLOCK.hp", 3)));
                    p.sendMessage(ChatColor.translateAlternateColorCodes('&', info));
                } catch (SQLException e) { e.printStackTrace(); }
            } else p.sendMessage(msg("no_region_here"));
            return true;
        }

        if (args[0].equalsIgnoreCase("delete")) {
            if (!hasP(p, "delete")) { p.sendMessage(msg("no_permission")); return true; }
            ApplicableRegionSet set = rm.getApplicableRegions(BukkitAdapter.asBlockVector(p.getLocation()));
            ProtectedRegion r = set.getRegions().stream().filter(rg -> rg.getId().startsWith("sk-")).findFirst().orElse(null);
            if (r != null) {
                if (!r.getOwners().contains(p.getUniqueId()) && !p.isOp()) { p.sendMessage(msg("not_owner")); return true; }
                if (deleteConfirms.containsKey(p.getUniqueId()) && (System.currentTimeMillis() - deleteConfirms.get(p.getUniqueId()) < 10000)) {
                    try (PreparedStatement ps = connection.prepareStatement("SELECT world, x, y, z FROM regions WHERE region_name = ?")) {
                        ps.setString(1, r.getId());
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            new Location(Bukkit.getWorld(rs.getString("world")), rs.getInt("x"), rs.getInt("y"), rs.getInt("z")).getBlock().setType(Material.AIR);
                        }
                    } catch (SQLException e) { e.printStackTrace(); }
                    rm.removeRegion(r.getId());
                    removeRegionFromDB(r.getId());
                    deleteConfirms.remove(p.getUniqueId());
                    p.sendMessage(msg("region_deleted"));
                } else {
                    deleteConfirms.put(p.getUniqueId(), System.currentTimeMillis());
                    p.sendMessage(msg("confirm_delete"));
                }
            } else p.sendMessage(msg("no_region_here"));
            return true;
        }

        if (args[0].equalsIgnoreCase("add") && args.length == 2) {
            if (!hasP(p, "add")) { p.sendMessage(msg("no_permission")); return true; }
            Block b = p.getTargetBlockExact(5);
            if (b == null) { p.sendMessage(msg("not_looking_at_block")); return true; }
            ApplicableRegionSet set = rm.getApplicableRegions(BukkitAdapter.asBlockVector(b.getLocation()));
            for (ProtectedRegion r : set) {
                if (r.getId().startsWith("sk-") && r.getOwners().contains(p.getUniqueId())) {
                    r.getMembers().addPlayer(Bukkit.getOfflinePlayer(args[1]).getUniqueId());
                    p.sendMessage(msg("player_added").replace("{player}", args[1]));
                    return true;
                }
            }
        }

        if (args[0].equalsIgnoreCase("remove") && args.length == 2) {
            if (!hasP(p, "remove")) { p.sendMessage(msg("no_permission")); return true; }
            Block b = p.getTargetBlockExact(5);
            if (b == null) { p.sendMessage(msg("not_looking_at_block")); return true; }
            ApplicableRegionSet set = rm.getApplicableRegions(BukkitAdapter.asBlockVector(b.getLocation()));
            for (ProtectedRegion r : set) {
                if (r.getId().startsWith("sk-") && r.getOwners().contains(p.getUniqueId())) {
                    r.getMembers().removePlayer(Bukkit.getOfflinePlayer(args[1]).getUniqueId());
                    p.sendMessage(msg("player_removed").replace("{player}", args[1]));
                    return true;
                }
            }
        }

        if (args[0].equalsIgnoreCase("list")) {
            if (!hasP(p, "list")) { p.sendMessage(msg("no_permission")); return true; }
            p.sendMessage("§bВаши регионы:");
            rm.getRegions().values().stream().filter(r -> r.getOwners().contains(p.getUniqueId())).forEach(r -> p.sendMessage("§7- " + r.getId()));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload") && hasP(p, "reload")) {
            reloadConfig(); p.sendMessage("§aКонфиг перезагружен!");
            return true;
        }
        return true;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(e.getPlayer().getWorld()));
        if (rm == null) return;
        ApplicableRegionSet set = rm.getApplicableRegions(BukkitAdapter.asBlockVector(e.getTo()));
        String cur = set.getRegions().stream().filter(r -> r.getId().startsWith("sk-")).map(ProtectedRegion::getId).findFirst().orElse(null);
        String last = lastRegions.get(e.getPlayer().getUniqueId());
        if (cur != null && !cur.equals(last)) {
            e.getPlayer().sendMessage(msg("enter_region"));
            lastRegions.put(e.getPlayer().getUniqueId(), cur);
        } else if (cur == null && last != null) {
            e.getPlayer().sendMessage(msg("exit_region"));
            lastRegions.remove(e.getPlayer().getUniqueId());
        }
    }
}