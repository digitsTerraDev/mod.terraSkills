package com.terraskills.progression;

import com.terraskills.config.TerraSkillsConfig;
import com.terraskills.integration.NutritionSkillsBridge;
import net.dries007.tfc.common.component.food.INutritionData;
import net.dries007.tfc.common.component.food.Nutrient;
import net.dries007.tfc.common.player.IPlayerInfo;
import net.minecraft.server.level.ServerPlayer;

public final class SkillPointGeneration {
    public record Breakdown(double baseline, double nutritionBeforeBalance, double balanceMultiplier,
                            double statMultiplier, double totalPerDay) {}
    public record NutrientValue(double fullness, double pointsPerDay) {}
    public record NutritionBreakdown(NutrientValue grain, NutrientValue fruit, NutrientValue vegetables,
                                     NutrientValue protein, NutrientValue dairy, double beforeBalance,
                                     double balanceScore, double balanceMultiplier, double afterBalance) {}

    private SkillPointGeneration() {}

    public static Breakdown calculate(ServerPlayer player, SkillTreeDefinition tree) {
        return calculate(player, tree.primary(), tree.secondary());
    }

    /** Calculates the work-rate for one research item using its own stat affinities. */
    public static Breakdown calculate(ServerPlayer player, RpgStat primaryStat, RpgStat secondaryStat) {
        NutritionBreakdown nutrition = calculateNutrition(player);
        int primary = PlayerProgress.getStat(player, primaryStat);
        int secondary = PlayerProgress.getStat(player, secondaryStat);
        double statMultiplier = 1
                + primary * TerraSkillsConfig.PRIMARY_BONUS_PER_STAT.get()
                + secondary * TerraSkillsConfig.SECONDARY_BONUS_PER_STAT.get();
        statMultiplier = Math.min(statMultiplier, TerraSkillsConfig.MAXIMUM_STAT_MULTIPLIER.get());

        double baseline = TerraSkillsConfig.BASE_POINTS_PER_DAY.get();
        double total = (baseline + nutrition.afterBalance()) * statMultiplier;
        return new Breakdown(baseline, nutrition.beforeBalance(), nutrition.balanceMultiplier(), statMultiplier, total);
    }

    public static NutritionBreakdown calculateNutrition(ServerPlayer player) {
        INutritionData nutrition = IPlayerInfo.get(player).nutrition();
        double exponent = TerraSkillsConfig.NUTRITION_EXPONENT.get();
        NutrientValue grain = nutrientValue(nutrition, Nutrient.GRAIN, TerraSkillsConfig.GRAIN_POINTS.get(), exponent);
        NutrientValue fruit = nutrientValue(nutrition, Nutrient.FRUIT, TerraSkillsConfig.FRUIT_POINTS.get(), exponent);
        NutrientValue vegetables = nutrientValue(nutrition, Nutrient.VEGETABLES, TerraSkillsConfig.VEGETABLE_POINTS.get(), exponent);
        NutrientValue protein = nutrientValue(nutrition, Nutrient.PROTEIN, TerraSkillsConfig.PROTEIN_POINTS.get(), exponent);
        NutrientValue dairy = nutrientValue(nutrition, Nutrient.DAIRY, TerraSkillsConfig.DAIRY_POINTS.get(), exponent);
        double nutritionRate = grain.pointsPerDay + fruit.pointsPerDay + vegetables.pointsPerDay
                + protein.pointsPerDay + dairy.pointsPerDay;

        double totalDistance = 0;
        int comparisons = 0;
        for (int first = 0; first < Nutrient.VALUES.length; first++) {
            double firstValue = Math.clamp(nutrition.getNutrient(Nutrient.VALUES[first]), 0, 1);
            for (int second = first + 1; second < Nutrient.VALUES.length; second++) {
                double secondValue = Math.clamp(nutrition.getNutrient(Nutrient.VALUES[second]), 0, 1);
                totalDistance += Math.abs(firstValue - secondValue);
                comparisons++;
            }
        }
        double balanceScore = comparisons == 0 ? 1 : 1 - totalDistance / comparisons;
        double balance = Math.pow(balanceScore, TerraSkillsConfig.BALANCE_EXPONENT.get());
        double balanceMultiplier = TerraSkillsConfig.MINIMUM_BALANCE_MULTIPLIER.get()
                + (1 - TerraSkillsConfig.MINIMUM_BALANCE_MULTIPLIER.get()) * balance;
        return new NutritionBreakdown(grain, fruit, vegetables, protein, dairy, nutritionRate,
                balanceScore, balanceMultiplier, nutritionRate * balanceMultiplier);
    }

    public static boolean awardWholePoints(ServerPlayer player, SkillTreeDefinition tree) {
        int points = (int) Math.floor(PlayerProgress.accumulatedPoints(player, tree.id()));
        if (points <= 0 || !NutritionSkillsBridge.addNutritionPoints(player, tree.id(), points)) return false;
        PlayerProgress.setAccumulatedPoints(player, tree.id(), PlayerProgress.accumulatedPoints(player, tree.id()) - points);
        return true;
    }

    private static double contribution(INutritionData data, Nutrient nutrient, double fullRate, double exponent) {
        return fullRate * Math.pow(Math.clamp(data.getNutrient(nutrient), 0, 1), exponent);
    }

    private static NutrientValue nutrientValue(INutritionData data, Nutrient nutrient, double fullRate, double exponent) {
        double fullness = Math.clamp(data.getNutrient(nutrient), 0, 1);
        return new NutrientValue(fullness, fullRate * Math.pow(fullness, exponent));
    }
}
