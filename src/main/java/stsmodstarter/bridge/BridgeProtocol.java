package stsmodstarter.bridge;

import java.util.ArrayList;

public final class BridgeProtocol {
    private BridgeProtocol() {
    }

    public static class CommandEnvelope {
        public String id;
        public String action;
        public String card_uuid;
        public Integer card_index;
        public Integer target_index;
        public Integer reward_index;
        public Integer option_index;
        public Integer x;
        public Integer y;
    }

    public static class CommandResponse {
        public String id;
        public boolean ok;
        public String message;
        public String error;
        public StateSnapshot state;
    }

    public static class StateSnapshot {
        public long timestamp_ms;
        public String runtime_dir;
        public boolean in_game;
        public String context;
        public String current_screen;
        public String main_menu_screen;
        public String room_phase;
        public String room_type;
        public Integer act_num;
        public Integer floor_num;
        public Boolean first_room_chosen;
        public String selected_character;
        public PlayerState player;
        public ArrayList<CreaturePowerState> player_powers = new ArrayList<CreaturePowerState>();
        public ArrayList<CardState> hand = new ArrayList<CardState>();
        public ArrayList<CardState> draw_pile = new ArrayList<CardState>();
        public ArrayList<CardState> discard_pile = new ArrayList<CardState>();
        public ArrayList<CardState> exhaust_pile = new ArrayList<CardState>();
        public ArrayList<CardState> master_deck = new ArrayList<CardState>();
        public ArrayList<MonsterState> monsters = new ArrayList<MonsterState>();
        public ArrayList<RewardState> room_rewards = new ArrayList<RewardState>();
        public ArrayList<OptionState> card_reward_choices = new ArrayList<OptionState>();
        public ArrayList<OptionState> boss_reward_choices = new ArrayList<OptionState>();
        public ArrayList<OptionState> event_options = new ArrayList<OptionState>();
        public ArrayList<OptionState> campfire_options = new ArrayList<OptionState>();
        public ArrayList<OptionState> hand_select_cards = new ArrayList<OptionState>();
        public ArrayList<OptionState> grid_select_cards = new ArrayList<OptionState>();
        public ArrayList<OptionState> main_menu_options = new ArrayList<OptionState>();
        public ArrayList<OptionState> character_options = new ArrayList<OptionState>();
        public ArrayList<ShopCardState> shop_cards = new ArrayList<ShopCardState>();
        public ArrayList<ShopItemState> shop_relics = new ArrayList<ShopItemState>();
        public ArrayList<ShopItemState> shop_potions = new ArrayList<ShopItemState>();
        public ShopPurgeState shop_purge;
        public TreasureChestState treasure_chest;
        public MapState map;
        public boolean confirm_available;
        public boolean confirm_enabled;
        public String confirm_context;
        public boolean cancel_available;
        public boolean cancel_enabled;
        public String cancel_context;
        public ArrayList<ActionDescriptor> available_actions = new ArrayList<ActionDescriptor>();
        public String last_error;
    }

    public static class PlayerState {
        public String name;
        public String character_id;
        public int current_hp;
        public int max_hp;
        public int block;
        public int gold;
        public int energy;
        public int max_orbs;
        public int filled_orbs;
        public String stance;
        public boolean end_turn_queued;
        public ArrayList<RelicState> relics = new ArrayList<RelicState>();
        public ArrayList<PotionState> potions = new ArrayList<PotionState>();
        public ArrayList<OrbState> orbs = new ArrayList<OrbState>();
    }

    public static class CreaturePowerState {
        public String id;
        public String name;
        public int amount;
        public String type;
    }

    public static class RelicState {
        public String id;
        public String name;
        public int counter;
        public String tier;
    }

    public static class PotionState {
        public String id;
        public String name;
        public int slot;
        public int potency;
        public boolean can_use;
        public boolean target_required;
    }

    public static class OrbState {
        public String id;
        public String name;
        public int evoke_amount;
        public int passive_amount;
    }

    public static class CardState {
        public Integer index;
        public String uuid;
        public String id;
        public String name;
        public int cost;
        public int cost_for_turn;
        public int damage;
        public int block;
        public int magic_number;
        public boolean upgraded;
        public boolean exhaust;
        public boolean ethereal;
        public boolean retain;
        public boolean free_to_play_once;
        public boolean playable;
        public boolean requires_target;
        public String target;
        public String type;
        public String rarity;
        public String color;
        public String description;
    }

    public static class MonsterState {
        public Integer index;
        public String id;
        public String name;
        public int current_hp;
        public int max_hp;
        public int block;
        public boolean is_dead_or_escaped;
        public boolean is_escaping;
        public String intent;
        public Integer intent_damage;
        public Integer intent_base_damage;
        public boolean intent_multi_damage;
        public Integer intent_multi_amount;
        public ArrayList<CreaturePowerState> powers = new ArrayList<CreaturePowerState>();
    }

    public static class RewardState {
        public Integer index;
        public String type;
        public String text;
        public int gold;
        public String relic_name;
        public String potion_name;
        public ArrayList<String> cards = new ArrayList<String>();
        public boolean is_done;
        public boolean claimable;
    }

    public static class OptionState {
        public Integer index;
        public String id;
        public String label;
        public boolean disabled;
        public String note;
    }

    public static class ShopCardState {
        public Integer index;
        public String uuid;
        public String id;
        public String name;
        public int price;
        public boolean affordable;
        public boolean colorless;
        public String description;
    }

    public static class ShopItemState {
        public Integer index;
        public String id;
        public String name;
        public int price;
        public boolean affordable;
        public boolean purchased;
        public String item_type;
    }

    public static class ShopPurgeState {
        public boolean available;
        public int price;
        public boolean affordable;
    }

    public static class TreasureChestState {
        public boolean available;
        public boolean is_open;
        public String relic_reward;
        public boolean gold_reward;
        public boolean cursed;
    }

    public static class MapState {
        public Integer current_x;
        public Integer current_y;
        public ArrayList<MapNodeState> available_nodes = new ArrayList<MapNodeState>();
        public ArrayList<MapNodeState> all_nodes = new ArrayList<MapNodeState>();
    }

    public static class MapNodeState {
        public Integer x;
        public Integer y;
        public String symbol;
        public String room_type;
        public boolean has_emerald_key;
        public boolean connected;
        public boolean reachable_now;
        public boolean taken;
        public ArrayList<String> parents = new ArrayList<String>();
        public ArrayList<String> children = new ArrayList<String>();
    }
    public static class ActionDescriptor {
        public String action;
        public String description;

        public ActionDescriptor() {
        }

        public ActionDescriptor(String action, String description) {
            this.action = action;
            this.description = description;
        }
    }
}
