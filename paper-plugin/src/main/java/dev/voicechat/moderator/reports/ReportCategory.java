package dev.voicechat.moderator.reports;

import org.bukkit.Material;

/** Categories available in the voice report GUI. */
public enum ReportCategory {

    SLUR(
            "Slur",
            "Using racial or discriminatory slurs",
            Material.BARRIER,
            10
    ),
    EXCESSIVE_SWEARING(
            "Excessive Swearing",
            "Repeated heavy profanity",
            Material.BLAZE_POWDER,
            12
    ),
    ABUSE(
            "Abuse / Harassment",
            "Targeted harassment or threats",
            Material.IRON_SWORD,
            14
    ),
    SEXUAL_HARASSMENT(
            "Sexual Harassment",
            "Unwanted sexual comments or behaviour",
            Material.MAGMA_CREAM,
            16
    ),
    OTHER(
            "Other",
            "Does not fit the other categories",
            Material.PAPER,
            22
    );

    /** Display name shown in the GUI item. */
    public final String displayName;
    /** Lore line shown in the GUI item. */
    public final String description;
    /** Material used for the GUI item. */
    public final Material material;
    /** Inventory slot (0-indexed, 3-row chest = 27 slots). */
    public final int slot;

    ReportCategory(String displayName, String description, Material material, int slot) {
        this.displayName = displayName;
        this.description = description;
        this.material    = material;
        this.slot        = slot;
    }
}
