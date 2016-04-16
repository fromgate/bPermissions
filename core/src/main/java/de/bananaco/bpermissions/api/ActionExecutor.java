package de.bananaco.bpermissions.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import de.bananaco.bpermissions.api.Calculable;
import de.bananaco.bpermissions.api.CalculableType;
import de.bananaco.bpermissions.api.Permission;
import de.bananaco.bpermissions.api.RecursiveGroupException;
import de.bananaco.bpermissions.api.World;
import de.bananaco.bpermissions.api.WorldManager;

import java.util.logging.Level;
import java.util.logging.Logger;

public class ActionExecutor {

    private static WorldManager wm = WorldManager.getInstance();

    public static boolean execute(String name, CalculableType type, String action, String value, String world) {
        Set<World> worlds = new HashSet<World>();
        // add all if null
        if (world == null || (world.equals("global") && wm.isUseGlobalUsers())) {
            worlds.addAll(wm.getAllWorlds());
        } else if (wm.getUseGlobalFiles()) {
            worlds.add(wm.getDefaultWorld());
        } else {
            worlds.add(wm.getWorld(world));
        }

        for (World w : worlds) {
            Calculable c = w.get(name, type);

            if (action.equalsIgnoreCase("addgroup")) {
                c.addGroup(value);
            } else if (action.equalsIgnoreCase("rmgroup")) {
                c.removeGroup(value);
            } else if (action.equalsIgnoreCase("setgroup")) {
                c.setGroup(value);
            } else if (action.equalsIgnoreCase("addperm")) {
                Permission perm = Permission.loadFromString(value);
                c.addPermission(perm.nameLowerCase(), perm.isTrue());
            } else if (action.equalsIgnoreCase("rmperm")) {
                c.removePermission(value);
            } else if (action.startsWith("addmeta") || action.startsWith("meta")) {
                if (action.split(":").length != 2) {
                    return false;
                }
                String meta = action.split(":")[1];
                c.setValue(meta, value);
            } else if (action.startsWith("rmmeta") || action.startsWith("cmeta")) {
                if (action.split(":").length != 2) {
                    return false;
                }
                String meta = action.split(":")[1];
                c.removeValue(meta);
            } else {
                return false;
            }

        }

        return true;
    }
}

