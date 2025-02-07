package de.minebench.plotsigns;

/*
 * PlotSigns
 * Copyright (C) 2018 Max Lee aka Phoenix616 (mail@moep.tv)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.BooleanFlag;
import com.sk89q.worldguard.protection.flags.DoubleFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class PlotSigns extends JavaPlugin {

    private Economy economy;
    private String signSellLine;
    private ArrayList<String> sellFormat;

    private Cache<UUID, String[]> writeIntents = CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.SECONDS).build();
    private Cache<UUID, List<String>> messageIntents = CacheBuilder.newBuilder().maximumSize(1000).build();

    public static NamespacedKey SIGN_REGION_KEY;
    public static StringFlag PLOT_TYPE_FLAG = new StringFlag("plot-type");
    public static BooleanFlag BUYABLE_FLAG = new BooleanFlag("buyable");
    public static DoubleFlag PRICE_FLAG = new DoubleFlag("price");

    @Override
    public void onLoad() {
        SIGN_REGION_KEY = new NamespacedKey(this, "region");
        PLOT_TYPE_FLAG = registerOrGetFlag(PLOT_TYPE_FLAG);
        BUYABLE_FLAG = registerOrGetFlag(BUYABLE_FLAG);
        PRICE_FLAG = registerOrGetFlag(PRICE_FLAG);
    }

    private <T extends Flag> T registerOrGetFlag(T flag) {
        try {
            WorldGuard.getInstance().getFlagRegistry().register(flag);
            return flag;
        } catch (FlagConflictException | IllegalStateException e) {
            return (T) WorldGuard.getInstance().getFlagRegistry().get(flag.getName());
        }
    }

    @Override
    public void onEnable() {
        loadConfig();
        if (!setupEconomy()) {
            getLogger().log(Level.SEVERE, "Failed to hook into Vault! The plugin will not run without it!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new SignListener(this), this);
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        getCommand("plotsigns").setExecutor(new PlotSignsCommand(this));
    }

    private boolean setupEconomy() {
        if (!getServer().getPluginManager().isPluginEnabled("Vault")) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    public void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        signSellLine = getConfig().getString("sign.sell");
        sellFormat = new ArrayList<>();
        for (String line : getConfig().getStringList("sign.sellformat")) {
            String format = ChatColor.translateAlternateColorCodes('&', line);
            if (ChatColor.stripColor(format).isEmpty()) {
                sellFormat.add(format);
            } else {
                sellFormat.add("");
                getLogger().log(Level.SEVERE, "Format strings can only contain color/formatting codes! '" + line + "' contains '" + ChatColor.stripColor(format) + "'!");
            }
        }
    }

    /**
     * Make a WorldGuard region buyable
     * @param region The region to make buyable
     * @param price The price the region should cost
     * @param type The right for the max region count, use null or empty string if it shouldn't be limited
     * @throws IllegalArgumentException If the region's id or the permission string is longer than 15 chars
     */
    public void makeRegionBuyable(ProtectedRegion region, double price, String type) throws IllegalArgumentException {
        if (type != null && type.length() > 15)
            throw new IllegalArgumentException("Type string can't be longer than 15 chars! (It might not fit on a sign)");
        if (region.getId().length() > 15)
            throw new IllegalArgumentException("The region's ID can't be longer than 15 chars! (It might not fit on a sign)");
        region.setFlag(BUYABLE_FLAG, true);
        region.setFlag(PRICE_FLAG, price);
        region.setFlag(PLOT_TYPE_FLAG, type == null || type.isEmpty() ? null : type);
    }

    /**
     * Buy a region for a player
     * @param player The player that should buy the region
     * @param region The region to buy
     * @param price The price of the region
     * @param type The region's type for the count
     * @throws BuyException if the player can't buy the region for whatever reason
     */
    public void buyRegion(Player player, ProtectedRegion region, double price, String type) throws BuyException {
        if (region.getFlag(BUYABLE_FLAG) == null || !region.getFlag(BUYABLE_FLAG)) {
            throw new BuyException(getLang("buy.not-for-sale", "region", region.getId()));
        }

        if (!getEconomy().has(player, price)) {
            throw new BuyException(getLang("buy.not-enough-money", "region", region.getId(), "price", String.valueOf(price)));
        }

        if (!checkTypeCount(player, player.getWorld(), type)) {
            throw new BuyException(getLang("buy.maximum-type-count", "region", region.getId(), "type", type));
        }

        double earnedPerOwner = price - getConfig().getDouble("tax.fixed", 0) - price * getConfig().getDouble("tax.share", 0);
        if (region.getOwners().size() > 1) {
            earnedPerOwner = earnedPerOwner / region.getOwners().size();
        }
        earnedPerOwner = Math.floor(earnedPerOwner * 100) / 100; // Make sure to round down to the second decimal point

        EconomyResponse withdraw = getEconomy().withdrawPlayer(player, price);
        if (!withdraw.transactionSuccess()) {
            throw new BuyException(withdraw.errorMessage);
        }

        getLogger().log(Level.INFO, player.getName() + "/" + player.getUniqueId() + " bought region " + region.getId() + " for " + price + (type == null || type.isEmpty() ? "" : " Type: " + type));

        if (region.getOwners().size() > 0) {
            for (UUID ownerId : region.getOwners().getUniqueIds()) {
                OfflinePlayer owner = getServer().getOfflinePlayer(ownerId);
                EconomyResponse deposit = getEconomy().depositPlayer(owner, earnedPerOwner);
                if (!deposit.transactionSuccess()) {
                    getLogger().log(Level.WARNING, "Error while depositing " + deposit.amount + " to " + owner.getName() + "/" + ownerId + " from region " + region.getId() + ". " + deposit.errorMessage);
                } else {
                    getLogger().log(Level.INFO, owner.getName() + "/" + ownerId + " received " + deposit.amount + " from sale of region " + region.getId() + ".");
                }

                String message = getLang("buy.your-plot-sold",
                            "region", region.getId(),
                            "buyer", player.getName(),
                            "earned", String.valueOf(earnedPerOwner),
                            "price", String.valueOf(price)
                );
                if (owner.getPlayer() != null) {
                    owner.getPlayer().sendMessage(message);
                } else {
                    registerMessageIntent(ownerId, message);
                }
            }
        }

        region.setFlag(BUYABLE_FLAG, false);
        if (region.getFlag(PRICE_FLAG) == null) {
            region.setFlag(PRICE_FLAG, price);
        }
        if (region.getFlag(PLOT_TYPE_FLAG) == null && type != null && !type.isEmpty()) {
            region.setFlag(PLOT_TYPE_FLAG, type);
        }
        region.getOwners().clear();
        region.getOwners().addPlayer(player.getUniqueId());

        if (getConfig().getBoolean("update-all-sell-signs")) {
            updateSignsInRegion(player, region, true);
        }
    }

    void updateSignsInRegion(Entity entity, ProtectedRegion region, boolean sold) {
        // Get min chunks and get some border around the region if sell signs are outside of it
        int chunkMinX = (region.getMinimumPoint().getBlockX() >> 4) - 1;
        int chunkMinZ = (region.getMinimumPoint().getBlockZ() >> 4) - 1;
        int chunkMaxX = (region.getMaximumPoint().getBlockX() >> 4) + 1;
        int chunkMaxZ = (region.getMaximumPoint().getBlockZ() >> 4) + 1;

        String[] signLines;
        if (sold) {
            signLines = getSignLinesSold(entity, region);
        } else {
            signLines = getSignLines(region);
        }

        for (int x = chunkMinX; x <= chunkMaxX; x++) {
            for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                if (entity.getWorld().isChunkLoaded(x, z)) {
                    Chunk chunk = entity.getWorld().getChunkAt(x, z);
                    for (BlockState state : chunk.getTileEntities()) {
                        if (state instanceof Sign) {
                            Sign sign = (Sign) state;
                            if (sign.getPersistentDataContainer().has(SIGN_REGION_KEY, PersistentDataType.STRING)) {
                                String regionId = sign.getPersistentDataContainer().get(SIGN_REGION_KEY, PersistentDataType.STRING);
                                if (region.getId().equals(regionId)) {
                                    for (int i = 0; i < signLines.length; i++) {
                                        sign.setLine(i, signLines[i]);
                                    }
                                    sign.update();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public boolean checkTypeCount(Player player, World world, String type) {
        if (type == null || type.isEmpty() || player.hasPermission("plotsigns.type." + type + ".unlimited") || player.hasPermission("plotsigns.group." + type + ".unlimited")) {
            return true;
        }

        int maxAmount = 1;
        if (getConfig().contains("type-counts.groups." + type) && player.hasPermission("plotsigns.group." + type)) {
            maxAmount = getConfig().getInt("type-counts.groups." + type);
        } else {
            for (int i = getConfig().getInt("type-counts.max-number"); i >= 0; i--) {
                if (player.hasPermission("plotsigns.type." + type + "." + i)) {
                    maxAmount = i;
                    break;
                }
            }
        }

        if (maxAmount == 0) {
            return false;
        }

        RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(new BukkitWorld(world));
        if (rm == null) {
            return false;
        }
        int count = 0;
        for (ProtectedRegion region : rm.getRegions().values()) {
            if (region.getOwners().contains(player.getUniqueId()) && region.getFlag(PLOT_TYPE_FLAG) != null && type.equals(region.getFlag(PLOT_TYPE_FLAG))) {
                count++;
                if (count >= maxAmount) {
                    return false;
                }
            }
        }

        return count < maxAmount;
    }

    public void registerMessageIntent(UUID playerId, String message) {
        List<String> messages = getMessageIntents(playerId);
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(message);
        messageIntents.put(playerId, messages);
    }

    public boolean hasMessageIntents(UUID playerId) {
        return getMessageIntents(playerId) != null;
    }

    public List<String> getMessageIntents(UUID playerId) {
        return messageIntents.getIfPresent(playerId);
    }

    public void removeMessageIntents(UUID playerId) {
        messageIntents.invalidate(playerId);
    }

    public void registerWriteIntent(UUID playerId, String[] lines) {
        writeIntents.put(playerId, lines);
    }

    public boolean hasWriteIntent(UUID playerId) {
        return writeIntents.getIfPresent(playerId) != null;
    }

    public String[] getWriteIntent(UUID playerId) {
        return writeIntents.getIfPresent(playerId);
    }

    public void removeWriteIntent(UUID playerId) {
        writeIntents.invalidate(playerId);
    }
    
    /**
     * Get the lines that should go onto a sign for a specific region
     * @param region The region to sell
     * @return An array with the length 4 with the lines
     * @throws IllegalArgumentException when the region doesn't have a price set
     */
    public String[] getSignLines(ProtectedRegion region) throws IllegalArgumentException {
        if (region.getFlag(PRICE_FLAG) == null) {
            throw new IllegalArgumentException("The region " + region.getId() + " does not have the price flag set?");
        }
        String[] lines = new String[4];
        lines[0] = getSellLine();
        lines[1] = region.getId();
        lines[2] = String.valueOf(region.getFlag(PRICE_FLAG));
        lines[3] = region.getFlag(PLOT_TYPE_FLAG) != null ? region.getFlag(PLOT_TYPE_FLAG) : "";
        
        for (int i = 0; i < getSellFormat().size() && i < lines.length; i++) {
            lines[i] = getSellFormat().get(i) + lines[i];
        }
        return lines;
    }

    public String[] getSignLinesSold(Entity entity, ProtectedRegion region) {
        String[] lines = new String[4];
        List<String> configLines = getConfig().getStringList("sign.sold");

        for (int i = 0; i < lines.length; i++) {
            if (i < configLines.size()) {
                lines[i] = ChatColor.translateAlternateColorCodes('&',configLines.get(i))
                        .replace("%region%", region.getId())
                        .replace("%player%", entity.getName());
            } else {
                lines[i] = "";
            }
        }
        return lines;
    }

    public String getLang(String key, String... args) {
        String message = getConfig().getString("lang." + key, getName() + ": &cUnknown language key &6" + key + "&c!");
        for (int i = 0; i + 1 < args.length; i += 2) {
            message = message.replace("%" + args[i] + "%", args[i+1] != null ? args[i+1] : "null");
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public Economy getEconomy() {
        return economy;
    }

    public String getSellLine() {
        return signSellLine;
    }
    
    public ArrayList<String> getSellFormat() {
        return sellFormat;
    }

    public class BuyException extends Exception {
        public BuyException(String message) {
            super(message);
        }
    }
}
