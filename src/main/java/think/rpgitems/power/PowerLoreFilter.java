package think.rpgitems.power;

import org.bukkit.configuration.ConfigurationSection;
import think.rpgitems.commands.ArgumentPriority;

/**
 * Power lorefilter.
 * <p>
 * Not a triggerable power.
 * Preserve all lore lines match the {@link #regex}.
 * </p>
 */
public class PowerLoreFilter extends Power {
    /**
     * Regex to filter the lore
     */
    @ArgumentPriority
    public String regex = null;
    /**
     * Display text
     */
    @ArgumentPriority(value = 1, required = true)
    public String desc = "";

    @Override
    public void init(ConfigurationSection s) {
        regex = s.getString("regex", null);
        desc = s.getString("desc", "");
    }

    @Override
    public void save(ConfigurationSection s) {
        s.set("regex", regex);
        s.set("desc", desc);
    }

    @Override
    public String getName() {
        return "lorefilter";
    }

    @Override
    public String displayText() {
        return desc;
    }
}
