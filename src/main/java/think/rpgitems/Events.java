package think.rpgitems;

import cat.nyaa.nyaacore.utils.TridentUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingBreakEvent.RemoveCause;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import think.rpgitems.data.Context;
import think.rpgitems.data.LightContext;
import think.rpgitems.item.ItemManager;
import think.rpgitems.item.RPGItem;
import think.rpgitems.power.Pimpl;
import think.rpgitems.power.PowerManager;
import think.rpgitems.power.PowerSneak;
import think.rpgitems.power.PowerSprint;
import think.rpgitems.power.marker.Ranged;
import think.rpgitems.power.marker.RangedOnly;
import think.rpgitems.power.propertymodifier.MulModifier;
import think.rpgitems.power.trigger.BaseTriggers;
import think.rpgitems.power.trigger.Trigger;
import think.rpgitems.support.WGHandler;
import think.rpgitems.support.WGSupport;
import think.rpgitems.utils.ItemTagUtils;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Stream;

import static think.rpgitems.RPGItems.logger;
import static think.rpgitems.RPGItems.plugin;
import static think.rpgitems.item.RPGItem.TAG_MODIFIER;
import static think.rpgitems.power.Utils.maxWithCancel;
import static think.rpgitems.power.Utils.minWithCancel;

public class Events implements Listener {

    public static final String DAMAGE_SOURCE = "DamageSource";
    public static final String OVERRIDING_DAMAGE = "OverridingDamage";
    public static final String SUPPRESS_MELEE = "SuppressMelee";
    public static final String SUPPRESS_PROJECTILE = "SuppressProjectile";
    public static final String DAMAGE_SOURCE_ITEM = "DamageSourceItem";

    private static HashSet<Integer> removeProjectiles = new HashSet<>();
    private static HashMap<Integer, Integer> rpgProjectiles = new HashMap<>();
    private static Map<UUID, ItemStack> localItemStacks = new HashMap<>();

    private static RPGItem projectileRpgItem;
    private static ItemStack projectileItemStack;
    private static Player projectilePlayer;

    static private boolean canStack(ItemStack a, ItemStack b) {
        if (a != null && a.getType() == Material.AIR) a = null;
        if (b != null && b.getType() == Material.AIR) b = null;
        if (a == null && b == null) return true;
        if (a != null && b != null) {
            ItemStack ap = a.clone(), bp = b.clone();
            ap.setAmount(1);
            bp.setAmount(1);
            return ap.equals(bp);
        } else {
            return false;
        }
    }

    public static void registerLocalItemStack(UUID entityId, ItemStack item) {
        localItemStacks.put(entityId, item);
    }

    public static boolean hasLocalItemStack(UUID entityId) {
        return localItemStacks.containsKey(entityId);
    }

    public static ItemStack getLocalItemStack(UUID entityId) {
        return localItemStacks.get(entityId);
    }

    public static ItemStack removeLocalItemStack(UUID entityId) {
        return localItemStacks.remove(entityId);
    }

    public static void registerRPGProjectile(RPGItem rpgItem, ItemStack itemStack, Player player) {
        if (projectilePlayer != null) {
            throw new IllegalStateException();
        }
        Events.projectileRpgItem = rpgItem;
        Events.projectileItemStack = itemStack;
        Events.projectilePlayer = player;
    }

    public static void registerRPGProjectile(int entityId, int uid) {
        rpgProjectiles.put(entityId, uid);
    }

    public static void autoRemoveProjectile(int entityId) {
        removeProjectiles.add(entityId);
    }

    @EventHandler
    public void onItemEnchant(EnchantItemEvent e) {
        Optional<RPGItem> opt = ItemManager.toRPGItem(e.getItem());
        Player p = e.getEnchanter();
        if (opt.isPresent()) {
            RPGItem item = opt.get();
            checkEnchantPerm(e, p, item);
        }
    }

    @EventHandler
    public void onHangingBreak(HangingBreakEvent e) {
        if (e.getCause().equals(RemoveCause.EXPLOSION))
            if (e.getEntity().hasMetadata("RPGItems.Rumble")) {
                e.getEntity().removeMetadata("RPGItems.Rumble", plugin); // Allow the entity to be broken again
                e.setCancelled(true);
            }
    }

    @EventHandler
    public void onBreak(BlockPhysicsEvent e) { // Is not triggered when the block a torch is attached to is removed
        if (e.getChangedType().equals(Material.TORCH))
            if (e.getBlock().hasMetadata("RPGItems.Torch")) {
                e.setCancelled(true); // Cancelling this does not work
                e.getBlock().removeMetadata("RPGItems.Torch", plugin);
                e.getBlock().setType(Material.AIR);
            }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (block.getType().equals(Material.TORCH))
            if (block.hasMetadata("RPGItems.Torch"))
                e.setCancelled(true);

        Player player = e.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        RPGItem rItem;
        if ((rItem = ItemManager.toRPGItem(item).orElse(null)) == null) {
            return;
        }

        boolean can = rItem.breakBlock(player, item, block);
        if (!can) {
            e.setCancelled(true);
        }
        if (rItem.getItemStackDurability(item).map(d -> d <= 0).orElse(false)) {
            player.getInventory().setItemInMainHand(null);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        final Projectile entity = e.getEntity();
        if (removeProjectiles.contains(entity.getEntityId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                removeProjectiles.remove(entity.getEntityId());
                entity.remove();
            });
        }
        if (rpgProjectiles.containsKey(entity.getEntityId())) {
            try {
                RPGItem rItem = ItemManager.getItem(rpgProjectiles.get(entity.getEntityId())).orElse(null);

                if (rItem == null || !(entity.getShooter() instanceof Player))
                    return;
                Player player = (Player) entity.getShooter();
                if (player.isOnline() && !player.isDead()) {
                    ItemStack item = player.getInventory().getItemInMainHand();
                    RPGItem hItem = ItemManager.toRPGItem(item).orElse(null);

                    if (hasLocalItemStack(entity.getUniqueId())) {
                        item = getLocalItemStack(entity.getUniqueId());
                        rItem = ItemManager.toRPGItem(item).orElse(null);
                        if (rItem == null) throw new IllegalStateException();
                    } else {
                        if (rItem != hItem) {
                            item = player.getInventory().getItemInOffHand();
                            hItem = ItemManager.toRPGItem(item).orElse(null);
                            if (rItem != hItem) {
                                return;
                            }
                        }
                    }

                    List<Ranged> ranged = rItem.getMarker(Ranged.class, true);
                    if (!ranged.isEmpty()) {
                        double distance = player.getLocation().distance(e.getEntity().getLocation());
                        if (ranged.get(0).rm > distance || distance > ranged.get(0).r) {
                            return;
                        }
                    }
                    rItem.power(player, item, e, BaseTriggers.PROJECTILE_HIT);
                }
            } finally {
                Bukkit.getScheduler().runTask(plugin, () -> rpgProjectiles.remove(entity.getEntityId()));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerShootBow(EntityShootBowEvent e) {
        LivingEntity entity = e.getEntity();
        float force = e.getForce();
        if (entity instanceof Player) {
            ItemStack bow = e.getBow();
            force = ItemManager.toRPGItem(bow).flatMap(rpgItem -> rpgItem.power(((Player) entity), bow, e, BaseTriggers.BOW_SHOOT)).orElse(force);
        }
        if (force == -1) {
            e.setCancelled(true);
            return;
        }
        if (e.getForce() != 0) {
            e.getProjectile().setMetadata("RPGItems.OriginalForce", new FixedMetadataValue(plugin, e.getForce()));
        }
        e.getProjectile().setMetadata("RPGItems.Force", new FixedMetadataValue(plugin, force));
    }

    @EventHandler
    public void onProjectileFire(ProjectileLaunchEvent e) {
        Projectile entity = e.getEntity();
        ProjectileSource shooter = entity.getShooter();
        if (!(shooter instanceof Player)) return;
        Player player = (Player) shooter;
        if (projectilePlayer != null) {
            if (projectilePlayer != player) {
                throw new IllegalStateException();
            }
            registerRPGProjectile(e.getEntity().getEntityId(), projectileRpgItem.getUid());
            projectileRpgItem.power(player, projectileItemStack, e, BaseTriggers.LAUNCH_PROJECTILE);
            projectileRpgItem = null;
            projectilePlayer = null;
            projectileItemStack = null;
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        RPGItem rItem = ItemManager.toRPGItem(item).orElse(null);
        if (entity instanceof Trident) {
            item = TridentUtils.getTridentItemStack((Trident) entity);
            rItem = ItemManager.toRPGItem(item).orElse(null);
            if (rItem == null) return;
            UUID uuid = entity.getUniqueId();
            registerLocalItemStack(uuid, item);
            ItemStack fakeItem = rItem.toItemStack();
            List<String> fakeLore = new ArrayList<>(1);
            fakeLore.add(uuid.toString());
            ItemMeta fakeItemItemMeta = fakeItem.getItemMeta();
            fakeItemItemMeta.setLore(fakeLore);
            fakeItem.setItemMeta(fakeItemItemMeta);
            TridentUtils.setTridentItemStack((Trident) entity, fakeItem);
        } else {
            if (rItem == null) {
                item = player.getInventory().getItemInOffHand();
                rItem = ItemManager.toRPGItem(item).orElse(null);
                if (rItem == null) {
                    return;
                }
            }
        }
        if (!rItem.hasMarker(Ranged.class) && !rItem.hasMarker(RangedOnly.class) && item.getType() != Material.BOW && item.getType() != Material.SNOWBALL && item.getType() != Material.EGG && item.getType() != Material.POTION && item.getType() != Material.TRIDENT) {
            return;
        }
        if (ItemManager.canUse(player, rItem) == Event.Result.DENY) {
            return;
        }
        registerRPGProjectile(e.getEntity().getEntityId(), rItem.getUid());
        rItem.power(player, item, e, BaseTriggers.LAUNCH_PROJECTILE);
    }

    @EventHandler
    public void onPlayerAction(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        Action action = e.getAction();
        Material im = e.getMaterial();
        if (action == Action.PHYSICAL || im == Material.AIR) return;
        if ((action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) && (im == Material.BOW || im == Material.SNOWBALL || im == Material.EGG || im == Material.POTION || im == Material.TRIDENT))
            return;
        RPGItem rItem = ItemManager.toRPGItem(e.getItem()).orElse(null);
        if (rItem == null) return;
        if (e.getHand() == EquipmentSlot.OFF_HAND) {
            rItem.power(player, e.getItem(), e, BaseTriggers.OFFHAND_CLICK);
        } else if (action == Action.RIGHT_CLICK_AIR) {
            rItem.power(player, e.getItem(), e, BaseTriggers.RIGHT_CLICK);
        } else if (action == Action.RIGHT_CLICK_BLOCK &&
                           !(e.getClickedBlock().getType().isInteractable() && !player.isSneaking())) {
            rItem.power(player, e.getItem(), e, BaseTriggers.RIGHT_CLICK);
        } else if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            rItem.power(player, e.getItem(), e, BaseTriggers.LEFT_CLICK);
        }
    }

    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) {
            return;
        }
        Player p = e.getPlayer();
        Trigger<PlayerToggleSneakEvent, PowerSneak, Void, Void> trigger = BaseTriggers.SNEAK;

        trigger(p, e, p.getInventory().getItemInMainHand(), trigger);

        ItemStack[] armorContents = p.getInventory().getArmorContents();
        Stream.of(armorContents)
              .forEach(i -> trigger(p, e, i, trigger));
    }

    <TEvent extends Event, TPower extends Pimpl, TResult, TReturn> TReturn trigger(Player player, TEvent event, ItemStack itemStack, Trigger<TEvent, TPower, TResult, TReturn> trigger) {
        Optional<RPGItem> rpgItem = ItemManager.toRPGItem(itemStack);
        return rpgItem.map(r -> r.power(player, itemStack, event, trigger)).orElse(null);
    }

    @EventHandler
    public void onPlayerSprint(PlayerToggleSprintEvent e) {
        if (!e.isSprinting()) {
            return;
        }
        Player p = e.getPlayer();
        Trigger<PlayerToggleSprintEvent, PowerSprint, Void, Void> sprint = BaseTriggers.SPRINT;

        trigger(p, e, p.getInventory().getItemInMainHand(), sprint);
        ItemStack[] armorContents = p.getInventory().getArmorContents();
        Stream.of(armorContents)
              .forEach(i -> trigger(p, e, i, sprint));

    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent e) {
        Player player = e.getPlayer();
        ItemStack ois = e.getOffHandItem();
        ItemStack mis = e.getMainHandItem();
        RPGItem mitem = ItemManager.toRPGItem(mis).orElse(null);
        RPGItem oitem = ItemManager.toRPGItem(ois).orElse(null);

        if (mitem != null) {
            Boolean cont = mitem.power(player, mis, e, BaseTriggers.SWAP_TO_MAINHAND);
            if (!cont) e.setCancelled(true);
        }

        if (oitem != null) {
            Boolean cont = oitem.power(player, ois, e, BaseTriggers.SWAP_TO_OFFHAND);
            if (!cont) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onOffhandInventoryClick(InventoryClickEvent e) {
        if (e.getInventory().getType() != InventoryType.CRAFTING || e.getSlotType() != InventoryType.SlotType.QUICKBAR || e.getSlot() != 40)
            return;
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player player = (Player) e.getWhoClicked();
        ItemStack currentIs = e.getCurrentItem();
        ItemStack cursorIs = e.getCursor();
        RPGItem currentItem = ItemManager.toRPGItem(currentIs).orElse(null);
        RPGItem cursorItem = ItemManager.toRPGItem(cursorIs).orElse(null);

        if (currentItem != null && (e.getAction() == InventoryAction.PICKUP_SOME || e.getAction() == InventoryAction.PICKUP_ALL || e.getAction() == InventoryAction.PICKUP_ONE || e.getAction() == InventoryAction.PICKUP_HALF || e.getAction() == InventoryAction.DROP_ALL_SLOT || e.getAction() == InventoryAction.DROP_ONE_CURSOR)) {
            Boolean cont = currentItem.power(player, currentIs, e, BaseTriggers.PICKUP_OFF_HAND);
            if (!cont) e.setCancelled(true);
        }

        if (cursorItem != null && (e.getAction() == InventoryAction.PLACE_SOME || e.getAction() == InventoryAction.PLACE_ONE || e.getAction() == InventoryAction.PLACE_ALL)) {
            Boolean cont = cursorItem.power(player, cursorIs, e, BaseTriggers.PLACE_OFF_HAND);
            if (!cont) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        ItemStack item;
        if (e.getHand() == EquipmentSlot.OFF_HAND) {
            item = e.getPlayer().getInventory().getItemInOffHand();
        } else {
            item = e.getItemInHand();
        }
        if (item.getType() == Material.AIR)
            return;

        RPGItem rItem = ItemManager.toRPGItem(item).orElse(null);
        if (rItem == null)
            return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        PlayerInventory in = player.getInventory();
        for (int i = 0; i < in.getSize(); i++) {
            ItemStack item = in.getItem(i);
            ItemManager.toRPGItem(item).ifPresent(rpgItem -> rpgItem.updateItem(item));
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            ItemManager.toRPGItem(item).ifPresent(rpgItem -> rpgItem.updateItem(item));
        }
        if (WGSupport.hasSupport() && WGSupport.useWorldGuard) {
            WGHandler.onPlayerJoin(e);
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPickupTrident(PlayerPickupArrowEvent e) {
        if (e.getItem().getItemStack().getType() != Material.TRIDENT || !e.getItem().getItemStack().hasItemMeta()) {
            return;
        }
        ItemStack tridentItem = e.getItem().getItemStack();
        ItemMeta itemMeta = tridentItem.getItemMeta();
        if (!rpgProjectiles.containsKey(e.getArrow().getEntityId()) || !itemMeta.hasLore() || itemMeta.getLore().isEmpty()) {
            return;
        }
        try {
            UUID uuid = UUID.fromString(itemMeta.getLore().get(0));
            ItemStack realItem = removeLocalItemStack(uuid);
            if (realItem != null) {
                if (realItem.getType() == Material.AIR) {
                    e.getArrow().setPickupStatus(Arrow.PickupStatus.DISALLOWED);
                    e.getArrow().setPersistent(false);
                    e.setCancelled(true);
                    Bukkit.getScheduler().runTaskLater(RPGItems.plugin, () -> e.getArrow().remove(), 100L);
                } else {
                    RPGItem.updateItemStack(realItem);
                    e.getItem().setItemStack(realItem);
                }
            }
        } catch (IllegalArgumentException ex) {
            logger.log(Level.WARNING, "Exception when PlayerPickupArrowEvent. May be harmless.", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        final Player p = (Player) e.getEntity();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline()) return;
                PlayerInventory in = p.getInventory();
                for (int i = 0; i < in.getSize(); i++) {
                    ItemStack item = in.getItem(i);
                    ItemManager.toRPGItem(item).ifPresent(rpgItem -> rpgItem.updateItem(item));
                }
                for (ItemStack item : in.getArmorContents()) {
                    ItemManager.toRPGItem(item).ifPresent(rpgItem -> rpgItem.updateItem(item));
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        updatePlayerInventory(e.getInventory(), (Player) e.getPlayer(), e);
    }

    List<UUID> switchCooldown = new ArrayList<>();

    @EventHandler
    public void onPlayerChangeItem(PlayerItemHeldEvent ev) {
        Player player = ev.getPlayer();
        ItemStack item = player.getInventory().getItem(ev.getNewSlot());
        ItemManager.toRPGItem(item).ifPresent(rpgItem -> rpgItem.updateItem(item));
        if (switchCooldown.contains(player.getUniqueId())) return;
        ItemStack[] armorContents = player.getInventory().getArmorContents();
        for (int i = 0; i < armorContents.length; i++) {
            ItemStack stack = armorContents[i];
            ItemManager.toRPGItem(stack).ifPresent(rpgItem -> rpgItem.updateItem(stack));
        }
        ItemStack offhandItem = player.getInventory().getItemInOffHand();
        ItemManager.toRPGItem(offhandItem).ifPresent(rpgItem -> rpgItem.updateItem(offhandItem));
        switchCooldown.add(player.getUniqueId());
        new BukkitRunnable() {
            @Override
            public void run() {
                switchCooldown.remove(player.getUniqueId());
            }
        }.runTaskLater(plugin, 20);
    }


    private void updatePlayerInventory(Inventory inventory, Player p, InventoryEvent e) {
        Inventory in = inventory;
        Iterator<ItemStack> it = in.iterator();
        try {
            while (it.hasNext()) {
                ItemStack item = it.next();
                ItemManager.toRPGItem(item).ifPresent(rpgItem -> rpgItem.updateItem(item));
                ItemManager.toRPGItem(item).ifPresent(rpgItem -> rpgItem.updateItem(item));
            }
        } catch (ArrayIndexOutOfBoundsException ex) {
            logger.log(Level.WARNING, "Exception when InventoryOpenEvent. May be harmless.", ex);
            // Fix for the bug with anvils in craftbukkit
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getClickedInventory() instanceof AnvilInventory) {
            if (e.getRawSlot() == 2) {
                HumanEntity p = e.getWhoClicked();
                ItemStack ind1 = e.getView().getItem(0);
                ItemStack ind2 = e.getView().getItem(1);
                Optional<RPGItem> opt1 = ItemManager.toRPGItem(ind1);
                Optional<RPGItem> opt2 = ItemManager.toRPGItem(ind2);
                opt1.ifPresent(item -> checkEnchantPerm(e, p, item));
                opt2.ifPresent(item -> checkEnchantPerm(e, p, item));
            }
        }
    }

    public void checkEnchantPerm(Cancellable e, HumanEntity p, RPGItem item) {
        RPGItem.EnchantMode enchantMode = item.getEnchantMode();
        if (enchantMode == RPGItem.EnchantMode.DISALLOW) {
            e.setCancelled(true);
        } else if (enchantMode == RPGItem.EnchantMode.PERMISSION && !(p.hasPermission("rpgitem.enchant." + item.getName()) || p.hasPermission("rpgitem.enchant." + item.getUid()))) {
            e.setCancelled(true);
        }
    }

    //this function cann't catch event when player open their backpack.
    //fuck there's no event when player open their backpack.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryOpen(final InventoryOpenEvent e) {
        if (e.getInventory().getHolder() == null || e.getInventory().getLocation() == null)
            return;
        if (e.getInventory().getType() != InventoryType.CHEST) {
            updatePlayerInventory(e.getInventory(), (Player) e.getPlayer(), e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent ev) {
        if (ev.getDamager() instanceof Player) {
            playerDamager(ev);
        } else if (ev.getDamager() instanceof Projectile) {
            projectileDamager(ev);
        }
    }

    private void playerDamager(EntityDamageByEntityEvent e) {
        Player player = (Player) e.getDamager();
        Entity entity = e.getEntity();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (e.getCause() == EntityDamageEvent.DamageCause.THORNS)
            return;

        Optional<Boolean> suppressMelee = LightContext.getTemp(player.getUniqueId(), SUPPRESS_MELEE);
        Optional<Double> overridingDamage = LightContext.getTemp(player.getUniqueId(), OVERRIDING_DAMAGE);
        Optional<ItemStack> sourceItem = LightContext.getTemp(player.getUniqueId(), DAMAGE_SOURCE_ITEM);

        if (!overridingDamage.isPresent()) {
            overridingDamage = Optional.ofNullable(Context.instance().getDouble(player.getUniqueId(), OVERRIDING_DAMAGE));
        }

        if (sourceItem.isPresent()) {
            item = sourceItem.get();
        } else {
            sourceItem = Optional.ofNullable((ItemStack) Context.instance().get(player.getUniqueId(), DAMAGE_SOURCE_ITEM));
            if (sourceItem.isPresent()) {
                item = sourceItem.get();
            }
        }

        if (suppressMelee.isPresent() && suppressMelee.get()) {
            overridingDamage.ifPresent(e::setDamage);
            return;
        } else {
            suppressMelee = Optional.ofNullable(Context.instance().getBoolean(player.getUniqueId(), SUPPRESS_MELEE));
            if (suppressMelee.isPresent() && suppressMelee.get()) {
                overridingDamage.ifPresent(e::setDamage);
                return;
            }
        }

        RPGItem rItem = ItemManager.toRPGItem(item).orElse(null);
        ItemStack[] armorContents = player.getInventory().getArmorContents();
        double originDamage = e.getDamage();
        double damage = originDamage;
        if (rItem != null && !overridingDamage.isPresent()) {
            damage = rItem.meleeDamage(player, originDamage, item, entity);
        } else if (overridingDamage.isPresent()) {
            damage = overridingDamage.get();
        }
        if (damage == -1) {
            e.setCancelled(true);
            return;
        }
        e.setDamage(damage);
        if (!(entity instanceof LivingEntity)) return;
        if (rItem != null) {
            damage = maxWithCancel(rItem.power(player, item, e, BaseTriggers.HIT).orElse(null), damage);
        }
        runHitTrigger(e, player, damage, armorContents);
    }

    private void projectileDamager(EntityDamageByEntityEvent e) {
        Projectile projectile = (Projectile) e.getDamager();
        Integer projectileID = rpgProjectiles.get(projectile.getEntityId());
        if (projectileID == null) {
            if (projectile.hasMetadata("RPGItems.OriginalForce")) {
                double damage = e.getDamage() * projectile.getMetadata("RPGItems.Force").get(0).asFloat() / projectile.getMetadata("RPGItems.OriginalForce").get(0).asFloat();
                e.setDamage(damage);
            }
            return;
        }
        RPGItem rItem = ItemManager.getItem(projectileID).orElse(null);
        if (rItem == null || !(projectile.getShooter() instanceof Player))
            return;
        if (!((Player) projectile.getShooter()).isOnline()) {
            return;
        }
        Player player = (Player) projectile.getShooter();
        ItemStack item = player.getInventory().getItemInMainHand();
        RPGItem hItem = ItemManager.toRPGItem(item).orElse(null);

        if (hasLocalItemStack(projectile.getUniqueId())) {
            item = getLocalItemStack(projectile.getUniqueId());
            rItem = ItemManager.toRPGItem(item).orElse(null);
            if (rItem == null) throw new IllegalStateException();
        } else {
            if (rItem != hItem) {
                item = player.getInventory().getItemInOffHand();
                hItem = ItemManager.toRPGItem(item).orElse(null);
                if (rItem != hItem) {
                    return;
                }
            }
        }

        Boolean suppressProjectile = Context.instance().getBoolean(player.getUniqueId(), SUPPRESS_PROJECTILE);
        Double overridingDamage = Context.instance().getDouble(player.getUniqueId(), OVERRIDING_DAMAGE);

        if (suppressProjectile != null && suppressProjectile) {
            if (overridingDamage != null) {
                e.setDamage(overridingDamage);
            }
            return;
        }

        double originDamage = e.getDamage();
        double damage;
        if (overridingDamage == null) {
            damage = rItem.projectileDamage(player, originDamage, item, projectile, e.getEntity());
        } else {
            damage = overridingDamage;
        }
        if (damage == -1) {
            e.setCancelled(true);
            return;
        }
        e.setDamage(damage);
        ItemStack[] armorContents = player.getInventory().getArmorContents();
        if (!(e.getEntity() instanceof LivingEntity)) return;
        damage = maxWithCancel(rItem.power(player, item, e, BaseTriggers.HIT).orElse(null), damage);
        runHitTrigger(e, player, damage, armorContents);
    }

    private void runHitTrigger(EntityDamageByEntityEvent e, Player player, double damage, ItemStack[] armorContents) {
        for (ItemStack armorContent : armorContents) {
            if (armorContent == null) continue;
            RPGItem rpgItem = ItemManager.toRPGItem(armorContent).orElse(null);
            if (rpgItem == null) continue;
            damage = maxWithCancel(rpgItem.power(player, armorContent, e, BaseTriggers.HIT).orElse(null), damage);
        }
        if (damage == -1) {
            e.setCancelled(true);
            e.setDamage(0);
        }
        e.setDamage(damage);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player) {
            Player player = (Player) e.getEntity();
            ItemStack[] armour = player.getInventory().getArmorContents();
            boolean hasRPGItem = false;
            double damage = e.getDamage();
            for (ItemStack pArmour : armour) {
                RPGItem pRItem = ItemManager.toRPGItem(pArmour).orElse(null);
                if (pRItem == null) {
                    continue;
                }
                hasRPGItem = true;
                Entity damager = null;
                if (e instanceof EntityDamageByEntityEvent) {
                    damager = ((EntityDamageByEntityEvent) e).getDamager();
                }
                damage = pRItem.takeDamage(player, damage, pArmour, damager);
            }
            if (hasRPGItem) {
                player.getInventory().setArmorContents(armour);
            }
            e.setDamage(damage);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerHitTaken(EntityDamageEvent ev) {
        if (ev.getEntity() instanceof Player) {
            ev.setDamage(playerHitTaken((Player) ev.getEntity(), ev));
        }
    }

    private double playerHitTaken(Player e, EntityDamageEvent ev) {
        double ret = ev.getDamage();
        for (ItemStack item : e.getInventory().getContents()) {
            RPGItem ri = ItemManager.toRPGItem(item).orElse(null);
            if (ri == null) continue;
            ret = minWithCancel(ri.power(e, item, ev, BaseTriggers.HIT_TAKEN).orElse(null), ret);
        }
        return ret;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void onPlayerHurt(EntityDamageByEntityEvent ev) {
        if (ev.getEntity() instanceof Player) {
            Player e = (Player) ev.getEntity();
            for (ItemStack item : e.getInventory().getContents()) {
                RPGItem ri = ItemManager.toRPGItem(item).orElse(null);
                if (ri == null) continue;
                ri.power(e, item, ev, BaseTriggers.HURT);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onItemDamage(PlayerItemDamageEvent e) {
        if (ItemManager.toRPGItem(e.getItem()).isPresent()) {
            e.setCancelled(true);
        }
    }
}