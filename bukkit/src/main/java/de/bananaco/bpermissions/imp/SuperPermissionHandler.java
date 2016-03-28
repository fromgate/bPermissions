package de.bananaco.bpermissions.imp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import de.bananaco.bpermissions.api.*;
import de.bananaco.bpermissions.util.loadmanager.TaskRunnable;
import de.bananaco.bpermissions.util.Debugger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

import de.bananaco.bpermissions.util.loadmanager.MainThread;

/**
 * Handles all the superperms registering/unregistering for
 * PermissionAttachments (it's basically just somewhere to stick all the nasty
 * SuperPerms stuff that wouldn't exist if SuperPerms was a more flexible
 * system.
 *
 * What's wrong with a PermissionProvider interface where we can register a
 * single PermissionProvider?!
 */
public class SuperPermissionHandler implements Listener {

    private WorldManager wm = WorldManager.getInstance();
    private Map<UUID, PermissionAttachment> attachments = new HashMap<UUID, PermissionAttachment>();
    private Permissions plugin;

    /**
     * This is put in place until such a time as Bukkit pull 466 is implemented
     * https://github.com/Bukkit/Bukkit/pull/466
     */
    public void setPermissions(bPermissible p, Plugin plugin, Map<String, Boolean> perm) {
        BukkitCompat.setPermissions(p, plugin, perm);
    }

    // Main constructor
    protected SuperPermissionHandler(Permissions plugin) {
        this.plugin = plugin;
        // This next bit is simply to make bPermissions.* work with superperms, since I now have my bulk adding, I will concede to this
        Map<String, Boolean> children = new HashMap<String, Boolean>();
        children.put("bPermissions.admin", true);
        Permission permission = new Permission("bPermissions.*", PermissionDefault.OP, children);
        if (plugin.getServer().getPluginManager().getPermission("bPermissions.*") == null) {
            plugin.getServer().getPluginManager().addPermission(permission);
        }
    }

    /**
     * A guaranteed way to setup all players in the server in one fell swoop
     */
    public void setupAllPlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            setupPlayer(player, false);
        }
    }

    /**
     * Set up the Player via the specified World object (note this is a
     * bPermissions world, not a Bukkit world)
     *
     * @param player
     * @param recalculate
     */
    public void setupPlayer(Player player, boolean recalculate) {
        if (!plugin.isEnabled()) {
            return;
        }

        bPermissible permissible = null;

        if (player instanceof bPermissible) {
            permissible = (bPermissible) player;
            ((bPermissible) player).setWorld(player.getWorld().getName());
        } else {
            permissible = new bPermissible(player);
            org.bukkit.permissions.Permissible oldpermissible = Injector.inject(player, permissible);
            permissible.setOldPermissible(oldpermissible);
            permissible.setWorld(player.getWorld().getName());
        }

        // Grab the pre-calculated effectivePermissions from the User object
        // Then whack it onto the player
        // TODO wait for the bukkit team to get their finger out, we'll use our reflection here!		
        Map<String, Boolean> perms = ApiLayer.getEffectivePermissions(
                player.getWorld().getName(),
                CalculableType.USER,
                player.getUniqueId().toString(),
                recalculate
        );

        // set the permissions onto the player's Permissible
        setPermissions(permissible, plugin, perms);

        // Set the metadata?
        String prefix = ApiLayer.getValue(
                player.getWorld().getName(),
                CalculableType.USER,
                player.getUniqueId().toString(),
                "prefix"
        );

        String suffix = ApiLayer.getValue(
                player.getWorld().getName(),
                CalculableType.USER,
                player.getUniqueId().toString(),
                "suffix"
        );

        // WTF
        player.setMetadata("prefix", new FixedMetadataValue(Permissions.instance, prefix));
        player.setMetadata("suffix", new FixedMetadataValue(Permissions.instance, suffix));

    }

    @EventHandler
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        MainThread mt = MainThread.getInstance();
        if (!mt.getStarted()) {
            event.disallow(Result.KICK_OTHER, "bPermissions not enabled");
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        // In theory this should be all we need to detect world, it isn't cancellable so... should be fine?
        setupPlayer(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) {
            return;
        }
        // Just to be doubly sure, I guess
        if (!event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            // schedule a check of the players permissions 1 tick after the teleport
            final Player player = event.getPlayer();
            final org.bukkit.World start = event.getFrom().getWorld();
            // setup the player
            Runnable r = new Runnable() {
                public void run() {
                    if (!start.equals(player.getWorld())) {
                        setupPlayer(player, false);
                    }
                }
            };
            // must be sync
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, r, 5);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        final String uuid = event.getUniqueId().toString();
        for (final de.bananaco.bpermissions.api.World world : wm.getAllWorlds()) {
            world.loadIfExists(uuid, CalculableType.USER);

            User user = (User) world.get(uuid, CalculableType.USER);
            try {
                user.calculateMappedPermissions();
                user.calculateEffectiveMeta();
            } catch (RecursiveGroupException e) {
                e.printStackTrace();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(final PlayerLoginEvent event) {
        final String uuid = event.getPlayer().getUniqueId().toString();
        Debugger.log("Player logged in with UUID " + uuid);

        // Likewise, in theory this should be all we need to detect when a player joins
        TaskRunnable r = new TaskRunnable() {
            @Override
            public TaskType getType() {
                return TaskType.SERVER;
            }

            public void run() {
                long time = System.currentTimeMillis();
                Debugger.log("Begun setup for " + uuid);

                setupPlayer(event.getPlayer(), false);

                long finish = System.currentTimeMillis() - time;
                Debugger.log("Setup for " + uuid + ". took " + finish + "ms.");
            }
        };
        MainThread.getInstance().schedule(r);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        Debugger.log("Player joined with UUID " + uuid);
    }
}