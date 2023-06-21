package de.mark225.bmtrails;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.LineMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Line;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class BMTrails extends JavaPlugin {
    public static record ConfigValue<T>(String key, T defaultValue) {

        public static FileConfiguration config;

        public static final ConfigValue<String> MARKER_SET_NAME = new ConfigValue<>("markerSetName", "Player Trails");
        public static final ConfigValue<Boolean> MARKER_SET_VISIBLE = new ConfigValue<>("markerSetVisibleDefault", true);
        public static final ConfigValue<Boolean> MARKER_SET_TOGGLEABLE = new ConfigValue<>("markerSetToggleable", true);
        public static final ConfigValue<List<String>> EXCLUDED_MAPS = new ConfigValue<>("excludedMaps", List.of());
        public static final ConfigValue<String> DEFAULT_COLOR = new ConfigValue<>("defaultTrailColor", "random");
        public static final ConfigValue<Boolean> PERMISSION_COLORS = new ConfigValue<>("usePermissionOverrides", false);
        public static final ConfigValue<List<String>> PERMISSION_OVERRIDES = new ConfigValue<>("permissionOverrides", List.of());
        public static final ConfigValue<Integer> POLLING_INTERVAL = new ConfigValue<>("pollingInterval", 20);
        public static final ConfigValue<Integer> MAX_TRAIL_POINTS = new ConfigValue<>("trailPointsMax", 100);
        public static final ConfigValue<Integer> TELEPORT_DETECTION_THRESHOLD = new ConfigValue<>("teleportDistance", 200);
        public static final ConfigValue<String> DISPLAY_NAME = new ConfigValue<>("displayName", "%player%");
        public static final ConfigValue<Boolean> PERMISSION_VISIBLE = new ConfigValue<>("usePermissionsForTrailVisibility", false);
        public static final ConfigValue<Integer> LINE_WIDTH = new ConfigValue<>("lineWidth", 2);
        public static final ConfigValue<Integer> MAX_DISTANCE = new ConfigValue<>("maxDistance", 1000);

        public T getValue(){
            Object fromConfig = config.get(key, defaultValue);
            try{
                return (T) fromConfig;
            }catch (ClassCastException e){
                BMTrails.getInstance().getLogger().log(Level.WARNING, "Config value for %s does not match expected data type".formatted(key));
            }
            return defaultValue;
        }
    }

    private static BMTrails bmTrails;
    private static final String PERM_VISIBLE = "bmtrails.visible";
    private static final String PERM_COLOR_PREFIX = "bmtrails.color.";
    private static final String PERM_CUSTOM_COLOR = "bmtrails.customcolor";

    private ConcurrentMap<UUID, ConcurrentLinkedDeque<Vector3d>> currentTrails;
    private ConcurrentMap<UUID, Color> colorCache;
    private ConcurrentMap<UUID, MarkerSet> markerSets;
    private ConcurrentMap<UUID, UUID> playerWorlds;
    private ConcurrentMap<UUID, String> nameCache;

    private Map<String, Color> colorPermissions;

    private List<Permission> permissions = new ArrayList<>();


    private BlueMapAPI blueMapAPI;

    private BukkitTask pollingTask;

    private boolean permissionFilter;

    private long lastUpdate = 0;
    private long lastCacheRefresh = 0;
    private int maxTrailLength;
    private String displayNamePreset;
    private Color defaultColor;
    private int defaultWidth;
    private int maxDistance;
    private int teleportDetectionThreshold;
    private boolean usePermissionColors;

    public static BMTrails getInstance(){
        return bmTrails;
    }

    @Override
    public void onEnable() {
        bmTrails = this;
        BlueMapAPI.onDisable((api) -> pollingTask.cancel());
        BlueMapAPI.onEnable((api) -> {
            blueMapAPI = api;
            getLogger().log(Level.INFO, "Enabling BMTrails");
            refreshConfig();
            createMarkerSets();
            registerPermissions();
            currentTrails = new ConcurrentHashMap<>();
            playerWorlds = new ConcurrentHashMap<>();
            lastUpdate = 0;
            lastCacheRefresh = 0;
            pollingTask = Bukkit.getScheduler().runTaskTimer(this, this::pollingTask, 0, ConfigValue.POLLING_INTERVAL.getValue());
        });
    }

    public void refreshConfig(){
        ConfigValue.config = getConfig();
        saveDefaultConfig();
        permissionFilter = Boolean.TRUE.equals(ConfigValue.PERMISSION_VISIBLE.getValue());
        maxTrailLength = ConfigValue.MAX_TRAIL_POINTS.getValue();
        displayNamePreset = ConfigValue.DISPLAY_NAME.getValue();
        String colorString = ConfigValue.DEFAULT_COLOR.getValue();
        try{
            defaultColor = new Color(0xff000000 | Integer.parseInt(colorString, 16));
        }catch(NumberFormatException e){
            defaultColor = null;
        }
        defaultWidth = ConfigValue.LINE_WIDTH.getValue();
        usePermissionColors = ConfigValue.PERMISSION_COLORS.getValue();
        Pattern overridePattern = Pattern.compile("[a-zA-Z0-9]+:[0-9a-fA-F]{6}");
        colorPermissions = ConfigValue.PERMISSION_OVERRIDES.getValue().stream()
                .filter(str -> overridePattern.matcher(str).matches())
                .map(str -> {
                    String[] parts = str.split(":");
                    return new AbstractMap.SimpleEntry<>(parts[0], new Color(0xff000000 | Integer.parseInt(parts[1], 16)));
                }).collect(Collectors.toMap(ent -> ent.getKey(), ent -> ent.getValue()));
        maxDistance = ConfigValue.MAX_DISTANCE.getValue();
        teleportDetectionThreshold = ConfigValue.TELEPORT_DETECTION_THRESHOLD.getValue();
    }

    private void createMarkerSets(){
        List<String> excludedMaps = ConfigValue.EXCLUDED_MAPS.getValue();
        markerSets = new ConcurrentHashMap<>();
        String name = ConfigValue.MARKER_SET_NAME.getValue();
        boolean visible = ConfigValue.MARKER_SET_VISIBLE.getValue();
        boolean toggleable = ConfigValue.MARKER_SET_TOGGLEABLE.getValue();
        for(World world : Bukkit.getWorlds()){
            BlueMapWorld bmw = blueMapAPI.getWorld(world.getUID()).orElse(null);
            if(bmw == null) continue;
            List<BlueMapMap> maps = bmw.getMaps().stream()
                    .filter(map -> !excludedMaps.contains(map.getId()))
                    .toList();
            if(maps.isEmpty()) continue;
            MarkerSet markerSet = MarkerSet.builder()
                    .label(name)
                    .defaultHidden(!visible)
                    .toggleable(toggleable)
                    .build();
            markerSets.put(world.getUID(), markerSet);
            maps.forEach(map -> map.getMarkerSets().put("bmtrails_" + map.getId() + "_" + world.getName(), markerSet));
        }
    }

    private void registerPermissions(){
        if(!permissions.isEmpty()) permissions.forEach(Bukkit.getPluginManager()::removePermission);
        permissions.clear();
        for(String colorName : colorPermissions.keySet()){
            Permission perm = new Permission(PERM_COLOR_PREFIX + colorName, PermissionDefault.FALSE);
            permissions.add(perm);
            Bukkit.getPluginManager().addPermission(perm);
        }
    }

    private void pollingTask(){
        Map<UUID, Location> locations = Bukkit.getOnlinePlayers().stream()
                .filter(player -> !permissionFilter || player.hasPermission(PERM_VISIBLE))
                .filter(player -> blueMapAPI.getWebApp().getPlayerVisibility(player.getUniqueId()))
                .filter(player -> markerSets.containsKey(player.getWorld().getUID()))
                .collect(Collectors.toMap(player -> player.getUniqueId(), player -> player.getLocation()));
        if(System.currentTimeMillis() - lastCacheRefresh >= 5000)
            refreshCaches();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> asyncCollectionTask(locations));
    }

    private Color resolveColor(Player player){
        if(usePermissionColors && player.hasPermission(PERM_CUSTOM_COLOR)){
            for(Map.Entry<String, Color> entry : colorPermissions.entrySet()){
                if(player.hasPermission(PERM_COLOR_PREFIX + entry.getKey())) return entry.getValue();
            }
        }
        if(defaultColor != null) return defaultColor;
        return new Color(0xff000000 | new Random(player.getName().hashCode()).nextInt());
    }

    private void refreshCaches(){
        lastCacheRefresh = System.currentTimeMillis();
        if(colorCache == null) colorCache = new ConcurrentHashMap<>();
        if(nameCache == null) nameCache = new ConcurrentHashMap<>();
        colorCache.clear();
        nameCache.clear();
        for(Player p : Bukkit.getOnlinePlayers()){
            if(usePermissionColors || permissionFilter)
                p.recalculatePermissions();
            colorCache.put(p.getUniqueId(), resolveColor(p));
            nameCache.put(p.getUniqueId(), ChatColor.stripColor(p.getDisplayName()));
        }
    }

    private void asyncCollectionTask(Map<UUID, Location> currentLocations){
        currentTrails.keySet().removeIf(key -> !currentLocations.containsKey(key));
        for(Map.Entry<UUID, Location> entry : currentLocations.entrySet()){
            UUID uuid = entry.getKey();
            Location loc = entry.getValue();
            Vector3d vector3d = Vector3d.from(loc.getX(), loc.getY(), loc.getZ());
            UUID world = loc.getWorld().getUID();
            UUID previousWorld = playerWorlds.put(uuid, world);
            if(previousWorld != null && previousWorld != world){
                currentTrails.put(uuid, new ConcurrentLinkedDeque<>(List.of(vector3d)));
            }else {
                Deque<Vector3d> deque = currentTrails.computeIfAbsent(uuid, key -> new ConcurrentLinkedDeque<>());

                if(!deque.isEmpty() && deque.peekFirst().distance(vector3d) > teleportDetectionThreshold)
                    deque.clear();

                deque.addFirst(vector3d);
                while (deque.size() > maxTrailLength && deque.size() > 0)
                    deque.removeLast();
            }
        }
        if(System.currentTimeMillis() - lastUpdate >= 10000)
            Bukkit.getScheduler().runTaskAsynchronously(this, this::asyncTrailTask);
    }

    private void asyncTrailTask(){
        lastUpdate = System.currentTimeMillis();
        cleanObsoleteMarkers();
        if(currentTrails.isEmpty()) return;
        for(Map.Entry<UUID, ConcurrentLinkedDeque<Vector3d>> entry : currentTrails.entrySet()){
            UUID world = playerWorlds.get(entry.getKey());
            if(world == null) continue;
            MarkerSet markerSet = markerSets.get(world);
            if(markerSet == null) continue;
            if(entry.getValue().size() > 1) {
                markerSet.put(entry.getKey().toString(), createMarker(entry.getKey(), entry.getValue()));
            }else{
                markerSet.remove(entry.getKey().toString());
            }
        }
    }

    private LineMarker createMarker(UUID player, Deque<Vector3d> points){
        Color color = colorCache.get(player);
        if(color == null){
            color = defaultColor;
            if(color == null) color = new Color(0xffffff);
        }
        String label = displayNamePreset.replace("%player%", nameCache.getOrDefault(player, "n/a"));
        Line line = new Line(points.toArray(Vector3d[]::new));
        return LineMarker.builder()
                .label(label)
                .detail(label)
                .line(line)
                .lineWidth(defaultWidth)
                .lineColor(color)
                .centerPosition()
                .maxDistance(maxDistance)
                .build();
    }


    private void cleanObsoleteMarkers(){
        for(Map.Entry<UUID, MarkerSet> entry : markerSets.entrySet()){
            MarkerSet markerSet = entry.getValue();
            markerSet.getMarkers().keySet().removeIf(key -> {
                UUID uuid = UUID.fromString(key);
                if(!currentTrails.containsKey(uuid)) return true;
                if(!entry.getKey().equals(playerWorlds.get(uuid))) return true;
                return false;
            });
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
