package top.colorgarden.core;

import arc.math.Mathf;
import arc.util.Log;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.Rules;
import mindustry.gen.Call;
import mindustry.maps.generators.PlanetGenerator;
import mindustry.type.Sector;
import mindustry.type.Weather;
import mindustry.type.Weather.WeatherEntry;
import top.colorgarden.config.CampaignConfig;
import top.colorgarden.util.I18n;

/**
 * Restores the two environment systems vanilla drives from the client: weather and the day/night
 * cycle.
 *
 * <p>Weather: {@code World#setSectorRules} fills {@code rules.weather} from the terrain and
 * {@code Logic#updateWeather} spawns the events; both run server-side, but only while the game is
 * actually ticking, and a sector saved without weather entries never gets them back. This service
 * re-runs the generator's weather pass on load and can force an event for testing.</p>
 *
 * <p>Day/night: {@code Universe#update} maps {@code Sector#getLight()} onto {@code rules.ambientLight}
 * and {@code rules.lighting}. That runs on the server too, but the values live in {@code Rules},
 * which clients only receive on an explicit {@code Call.setRules}. Since
 * {@code Universe.seconds} (the clock the light is derived from) only advances where the universe
 * is simulated, a client that does not simulate it renders a constant sky; the periodic
 * {@code setRules} below is what makes the cycle visible there.</p>
 */
public class EnvironmentService{
    /** Counts down to the next lighting broadcast. */
    private static float syncTimer;
    /** Last alpha pushed to clients, so an unchanged cycle costs nothing. */
    private static float lastAlpha = -1f;
    private static boolean lastLighting = true;

    public static void init(){
        //nothing to register: the two systems hook into the existing update loop
    }

    /**
     * Called once per world load, after the sector rules have been applied.
     *
     * <p>This deliberately does <b>not</b> recompute the weather. Vanilla fills {@code rules.weather}
     * once, while the sector is generated ({@code World#setSectorRules} -> {@code addWeather}), and the
     * result is stored in the sector save. Calling {@code addWeather} again on every load clears that
     * list and rebuilds it from the tiles as they are <i>now</i>, which can differ from the state the
     * sector was generated in - that is how an existing sector can silently lose its weather.</p>
     */
    public static void onWorldLoad(){
        if(!CampaignConfig.data().weatherSystem || !Vars.state.isCampaign()) return;

        try{
            describeSchedule();
        }catch(Throwable t){
            Log.info("[CampaignServer] weather report skipped: @", t.toString());
        }
    }

    /**
     * @return the tile statistics vanilla's {@code addWeather} looks at, plus the branch it would take.
     *         Printed by {@code campaign-weather floors} so the decision can be checked instead of
     *         guessed. Counted twice: as the world is now, and with border darkness ignored, which is
     *         the state the sector was generated in.
     */
    public static Seq<String> floorReport(){
        Seq<String> out = new Seq<>();
        if(Vars.world == null) return out;

        Sector sector = Vars.state.rules == null ? null : Vars.state.rules.sector;
        String preset = sector == null || sector.preset == null ? "none" : sector.preset.name;
        out.add("sector=" + (sector == null ? "-" : sector.name() + " (" + sector.id + ")")
            + " preset=" + preset + " planetGenerator="
            + (sector == null || sector.planet == null || sector.planet.generator == null
                ? "-" : sector.planet.generator.getClass().getSimpleName())
            + " borderDarkness=" + (Vars.state.rules != null && Vars.state.rules.borderDarkness));

        out.addAll(countFloors("now", true));
        out.addAll(countFloors("no-darkness", false));

        return out;
    }

    /** Counts floors the way {@code addWeather} does, optionally ignoring the border-darkness filter. */
    private static Seq<String> countFloors(String label, boolean respectDarkness){
        Seq<String> out = new Seq<>();

        arc.struct.ObjectIntMap<mindustry.world.Block> counts = new arc.struct.ObjectIntMap<>();
        arc.struct.ObjectSet<mindustry.type.Liquid> liquids = new arc.struct.ObjectSet<>();
        int skipped = 0;

        for(mindustry.world.Tile tile : Vars.world.tiles){
            if(respectDarkness && Vars.world.getDarkness(tile.x, tile.y) >= 3){
                skipped ++;
                continue;
            }

            if(tile.floor().liquidDrop != null) liquids.add(tile.floor().liquidDrop);
            if(tile.overlay() != null && tile.overlay().liquidDrop != null) liquids.add(tile.overlay().liquidDrop);

            if(!tile.block().isStatic()){
                counts.increment(tile.floor());
                if(tile.overlay() != mindustry.content.Blocks.air){
                    counts.increment(tile.overlay());
                }
            }
        }

        arc.struct.Seq<arc.struct.ObjectIntMap.Entry<mindustry.world.Block>> entries = counts.entries().toArray();
        entries.sort(e -> -e.value);
        entries.removeAll(e -> e.value < 30);

        out.add("[" + label + "] skippedByDarkness=" + skipped + " floorsOver30=" + entries.size
            + " liquids=" + liquids.size);

        int shown = 0;
        for(arc.struct.ObjectIntMap.Entry<mindustry.world.Block> entry : entries){
            if(shown ++ >= 5) break;
            out.add("  " + label + " " + entry.key.name + " x" + entry.value);
        }

        if(!entries.isEmpty()){
            mindustry.world.Block first = entries.first().key;
            boolean snow = first.name.contains("ice") || first.name.contains("snow");
            boolean rain = !snow && liquids.contains(mindustry.content.Liquids.water) && !first.name.contains("sand");
            boolean desert = !snow && !rain && first == mindustry.content.Blocks.sand;
            boolean spores = first.name.contains("spore") || first.name.contains("moss") || first.name.contains("tainted");
            out.add("[" + label + "] branches: snow=" + snow + " rain=" + rain
                + " desert=" + desert + " spores=" + spores);
        }

        return out;
    }

    /**
     * Recomputes the weather table the way the sector was generated: vanilla runs {@code addWeather}
     * while generating, before campaign border darkness exists, so re-running it later with darkness
     * active can pick a different dominant floor and drop the weather the sector originally had.
     *
     * @return the entries that ended up in the table
     */
    public static int refill(){
        Sector sector = Vars.state.rules == null ? null : Vars.state.rules.sector;
        if(sector == null || sector.planet == null || sector.planet.generator == null) return -1;

        boolean darkness = Vars.state.rules.borderDarkness;
        Vars.state.rules.borderDarkness = false;
        try{
            sector.planet.generator.addWeather(sector, Vars.state.rules);
        }finally{
            Vars.state.rules.borderDarkness = darkness;
        }

        return Vars.state.rules.weather.size;
    }

    /** Called from the tick hook. */
    public static void update(){
        updateDayNight();
    }

    private static void updateDayNight(){
        CampaignConfig config = CampaignConfig.data();
        if(!config.dayNightCycle || !Vars.state.isGame() || Vars.state.rules == null) return;

        Sector sector = Vars.state.rules.sector;
        if(sector == null || sector.planet == null) return;
        if(!sector.planet.updateLighting) return;
        if(sector.preset != null && sector.preset.noLighting) return;

        //same mapping as Universe#update: light in [0,1] -> ambient alpha through the planet's window
        float light = sector.getLight();
        float alpha = Mathf.clamp(Mathf.map(light, sector.planet.lightSrcFrom, sector.planet.lightSrcTo,
            sector.planet.lightDstFrom, sector.planet.lightDstTo));

        Rules rules = Vars.state.rules;
        rules.ambientLight.a = 1f - alpha;
        rules.lighting = !Mathf.equal(alpha, 1f);

        if(config.dayNightSyncSeconds <= 0f || Vars.net.client()) return;

        //clients that do not advance their own universe clock need the value pushed; only when it
        //actually moved, and only every few seconds, because a full setRules is not cheap
        syncTimer += arc.util.Time.delta;
        boolean moved = Math.abs(alpha - lastAlpha) > 0.02f || rules.lighting != lastLighting;
        if(syncTimer < config.dayNightSyncSeconds * 60f || !moved) return;

        syncTimer = 0f;
        lastAlpha = alpha;
        lastLighting = rules.lighting;
        Call.setRules(rules);
    }

    /** @return the weather entries currently scheduled for the loaded sector. */
    public static Seq<WeatherEntry> entries(){
        return Vars.state.rules == null ? new Seq<>() : Vars.state.rules.weather;
    }

    /** Prints the scheduled weather: name, intensity and the countdown until the next event. */
    public static void describeSchedule(){
        Seq<WeatherEntry> entries = entries();
        if(entries.isEmpty()){
            Log.info("[CampaignServer] " + I18n.get("campaign.weather.none"));
            return;
        }

        Log.info("[CampaignServer] " + I18n.get("campaign.weather.schedule", entries.size));
        for(WeatherEntry entry : entries){
            if(entry.weather == null) continue;
            Log.info("[CampaignServer]   @ intensity=@ cooldown=@ always=@",
                entry.weather.name, entry.intensity, Mathf.ceil(entry.cooldown / 60f), entry.always);
        }
    }

    /**
     * Spawns a weather event immediately, the same call {@code Logic#updateWeather} makes.
     *
     * @return false when the name does not match a weather type
     */
    public static boolean force(String name, float seconds){
        Weather weather = name == null ? null : Vars.content.weathers().find(w -> w.name.equalsIgnoreCase(name));
        if(weather == null && name != null){
            //fall back to the localized name so the console accepts what the player sees
            weather = Vars.content.weathers().find(w -> w.localizedName.equalsIgnoreCase(name));
        }
        if(weather == null) return false;

        arc.math.geom.Vec2 dir = new arc.math.geom.Vec2().setToRandomDirection();
        Call.createWeather(weather, 1f, Math.max(1f, seconds) * 60f, dir.x, dir.y);
        return true;
    }

    /** @return true when a weather event of that type is running right now. */
    public static boolean active(String name){
        return entries().contains(e -> e.weather != null && e.weather.name.equalsIgnoreCase(name) && e.weather.isActive());
    }
}
