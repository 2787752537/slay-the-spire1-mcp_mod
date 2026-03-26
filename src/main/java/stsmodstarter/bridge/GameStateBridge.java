package stsmodstarter.bridge;

import basemod.ReflectionHacks;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.EnergyManager;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.events.RoomEventDialog;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.CampfireUI;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton;
import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class GameStateBridge {
    private static final long STATE_WRITE_INTERVAL_MS = 150L;

    private final Json json;
    private long lastStateWriteAt;
    private String lastError;
    private String lastProcessedCommandId;

    public GameStateBridge() {
        this.json = new Json(JsonWriter.OutputType.json);
        this.json.setIgnoreUnknownFields(true);
    }

    public void tick() {
        BridgePaths.ensureRuntimeDir();
        processCommandIfPresent();
        long now = System.currentTimeMillis();
        if (now - this.lastStateWriteAt >= STATE_WRITE_INTERVAL_MS) {
            writeStateSnapshot();
            this.lastStateWriteAt = now;
        }
    }

    public void writeStateSnapshot() {
        writeJson(BridgePaths.stateFile(), buildState());
    }

    private void processCommandIfPresent() {
        Path commandPath = BridgePaths.commandFile();
        if (!Files.exists(commandPath)) {
            return;
        }
        try {
            String raw = new String(Files.readAllBytes(commandPath), StandardCharsets.UTF_8);
            BridgeProtocol.CommandEnvelope command = this.json.fromJson(BridgeProtocol.CommandEnvelope.class, raw);
            if (command == null || command.id == null || command.id.trim().isEmpty()) {
                writeResponse(null, false, null, "Invalid command payload");
            } else if (!command.id.equals(this.lastProcessedCommandId)) {
                BridgeProtocol.CommandResponse response = executeCommand(command);
                response.state = buildState();
                writeJson(BridgePaths.responseFile(), response);
                this.lastProcessedCommandId = command.id;
            }
        } catch (Exception exception) {
            this.lastError = exception.getMessage();
            writeResponse(null, false, null, "Command processing failed: " + exception.getMessage());
        } finally {
            try {
                Files.deleteIfExists(commandPath);
            } catch (IOException ignored) {
            }
        }
    }

    private void writeResponse(String id, boolean ok, String message, String error) {
        BridgeProtocol.CommandResponse response = new BridgeProtocol.CommandResponse();
        response.id = id;
        response.ok = ok;
        response.message = message;
        response.error = error;
        response.state = buildState();
        writeJson(BridgePaths.responseFile(), response);
    }

    private void writeJson(Path path, Object payload) {
        String content = this.json.prettyPrint(payload);
        Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.write(tempPath, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new RuntimeException("Failed to write file: " + path, exception);
        }
    }

    private BridgeProtocol.StateSnapshot buildState() {
        BridgeProtocol.StateSnapshot state = new BridgeProtocol.StateSnapshot();
        state.timestamp_ms = System.currentTimeMillis();
        state.runtime_dir = BridgePaths.runtimeDir().toString();
        state.in_game = AbstractDungeon.isPlayerInDungeon() && AbstractDungeon.player != null;
        state.current_screen = AbstractDungeon.screen == null ? "NONE" : AbstractDungeon.screen.name();
        state.first_room_chosen = AbstractDungeon.firstRoomChosen;
        state.last_error = this.lastError;
        if (!state.in_game) {
            state.context = "out_of_run";
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("ping", "Check whether the bridge is alive."));
            return state;
        }

        AbstractRoom room = AbstractDungeon.getCurrRoom();
        state.context = detectContext(room);
        state.act_num = AbstractDungeon.actNum;
        state.floor_num = AbstractDungeon.floorNum;
        state.room_phase = room == null ? "NONE" : safeEnum(room.phase);
        state.room_type = room == null ? "NONE" : room.getClass().getSimpleName();
        populatePlayerState(state);
        populateMonsterState(state, room);
        populateRewardsState(state, room);
        populateCardRewardState(state);
        populateEventState(state, room);
        populateCampfireState(state, room);
        populateSelectionStates(state);
        populateMapState(state);
        populateAvailableActions(state);
        return state;
    }

    private String detectContext(AbstractRoom room) {
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.HAND_SELECT) {
            return "hand_select";
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID) {
            return "grid_select";
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.CARD_REWARD) {
            return "card_reward";
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP) {
            return "map";
        }
        if (room != null && room.phase == AbstractRoom.RoomPhase.COMBAT) {
            return "combat";
        }
        if (room != null && room.event != null) {
            return "event";
        }
        if (room != null && room.rewardTime) {
            return "room_rewards";
        }
        if (room != null && room.getClass().getSimpleName().contains("RestRoom")) {
            return "campfire";
        }
        return "room";
    }
    private void populatePlayerState(BridgeProtocol.StateSnapshot state) {
        AbstractPlayer player = AbstractDungeon.player;
        BridgeProtocol.PlayerState playerState = new BridgeProtocol.PlayerState();
        playerState.name = player.name;
        playerState.character_id = player.chosenClass == null ? "" : player.chosenClass.name();
        playerState.current_hp = player.currentHealth;
        playerState.max_hp = player.maxHealth;
        playerState.block = player.currentBlock;
        playerState.gold = player.gold;
        playerState.energy = currentEnergy(player.energy);
        playerState.stance = player.stance == null ? "" : safe(player.stance.ID);
        playerState.end_turn_queued = player.endTurnQueued;
        for (AbstractRelic relic : player.relics) {
            BridgeProtocol.RelicState relicState = new BridgeProtocol.RelicState();
            relicState.id = relic.relicId;
            relicState.name = relic.name;
            relicState.counter = relic.counter;
            relicState.tier = safeEnum(relic.tier);
            playerState.relics.add(relicState);
        }
        for (AbstractPotion potion : player.potions) {
            BridgeProtocol.PotionState potionState = new BridgeProtocol.PotionState();
            potionState.id = potion.ID;
            potionState.name = potion.name;
            potionState.slot = potion.slot;
            potionState.potency = potion.getPotency();
            potionState.can_use = potion.canUse();
            potionState.target_required = potion.targetRequired;
            playerState.potions.add(potionState);
        }
        state.player = playerState;
        state.hand = buildCardStates(player.hand, true);
        state.draw_pile = buildCardStates(player.drawPile, false);
        state.discard_pile = buildCardStates(player.discardPile, false);
        state.exhaust_pile = buildCardStates(player.exhaustPile, false);
        state.master_deck = buildCardStates(player.masterDeck, false);
    }

    private ArrayList<BridgeProtocol.CardState> buildCardStates(CardGroup group, boolean includePlayableState) {
        ArrayList<BridgeProtocol.CardState> cards = new ArrayList<BridgeProtocol.CardState>();
        if (group == null || group.group == null) {
            return cards;
        }
        for (int index = 0; index < group.group.size(); index++) {
            AbstractCard card = group.group.get(index);
            BridgeProtocol.CardState cardState = new BridgeProtocol.CardState();
            cardState.index = index;
            cardState.uuid = card.uuid.toString();
            cardState.id = card.cardID;
            cardState.name = card.name;
            cardState.cost = card.cost;
            cardState.cost_for_turn = card.costForTurn;
            cardState.damage = card.damage;
            cardState.block = card.block;
            cardState.magic_number = card.magicNumber;
            cardState.upgraded = card.upgraded;
            cardState.exhaust = card.exhaust;
            cardState.ethereal = card.isEthereal;
            cardState.retain = card.retain || card.selfRetain;
            cardState.free_to_play_once = card.freeToPlayOnce;
            cardState.requires_target = requiresTarget(card);
            cardState.playable = includePlayableState && canPlayCard(card);
            cardState.target = safeEnum(card.target);
            cardState.type = safeEnum(card.type);
            cardState.rarity = safeEnum(card.rarity);
            cardState.color = safeEnum(card.color);
            cardState.description = safe(card.rawDescription);
            cards.add(cardState);
        }
        return cards;
    }

    private void populateMonsterState(BridgeProtocol.StateSnapshot state, AbstractRoom room) {
        if (room == null || room.monsters == null || room.monsters.monsters == null) {
            return;
        }
        for (int index = 0; index < room.monsters.monsters.size(); index++) {
            AbstractMonster monster = room.monsters.monsters.get(index);
            BridgeProtocol.MonsterState monsterState = new BridgeProtocol.MonsterState();
            monsterState.index = index;
            monsterState.id = monster.id;
            monsterState.name = monster.name;
            monsterState.current_hp = monster.currentHealth;
            monsterState.max_hp = monster.maxHealth;
            monsterState.block = monster.currentBlock;
            monsterState.is_dead_or_escaped = monster.isDeadOrEscaped();
            monsterState.is_escaping = monster.isEscaping || monster.escaped;
            monsterState.intent = monster.intent == null ? "" : monster.intent.name();
            monsterState.intent_damage = monster.getIntentDmg();
            monsterState.intent_base_damage = monster.getIntentBaseDmg();
            state.monsters.add(monsterState);
        }
    }

    private void populateRewardsState(BridgeProtocol.StateSnapshot state, AbstractRoom room) {
        if (room == null || room.rewards == null) {
            return;
        }
        for (int index = 0; index < room.rewards.size(); index++) {
            RewardItem reward = room.rewards.get(index);
            BridgeProtocol.RewardState rewardState = new BridgeProtocol.RewardState();
            rewardState.index = index;
            rewardState.type = safeEnum(reward.type);
            rewardState.text = safe(reward.text);
            rewardState.gold = reward.goldAmt;
            rewardState.relic_name = reward.relic == null ? null : reward.relic.name;
            rewardState.potion_name = reward.potion == null ? null : reward.potion.name;
            rewardState.is_done = reward.isDone;
            rewardState.claimable = !reward.isDone;
            if (reward.cards != null) {
                for (AbstractCard card : reward.cards) {
                    rewardState.cards.add(card.name);
                }
            }
            state.room_rewards.add(rewardState);
        }
    }

    private void populateCardRewardState(BridgeProtocol.StateSnapshot state) {
        if (AbstractDungeon.cardRewardScreen == null || AbstractDungeon.cardRewardScreen.rewardGroup == null) {
            return;
        }
        for (int index = 0; index < AbstractDungeon.cardRewardScreen.rewardGroup.size(); index++) {
            AbstractCard card = AbstractDungeon.cardRewardScreen.rewardGroup.get(index);
            BridgeProtocol.OptionState option = new BridgeProtocol.OptionState();
            option.index = index;
            option.id = card.cardID;
            option.label = card.name;
            option.disabled = false;
            option.note = safe(card.rawDescription);
            state.card_reward_choices.add(option);
        }
    }

    private void populateEventState(BridgeProtocol.StateSnapshot state, AbstractRoom room) {
        if (room == null || room.event == null) {
            return;
        }
        ArrayList<LargeDialogOptionButton> buttons = new ArrayList<LargeDialogOptionButton>();
        if (room.event.imageEventText != null && room.event.imageEventText.optionList != null && !room.event.imageEventText.optionList.isEmpty()) {
            buttons.addAll(room.event.imageEventText.optionList);
        } else if (RoomEventDialog.optionList != null && !RoomEventDialog.optionList.isEmpty()) {
            buttons.addAll(RoomEventDialog.optionList);
        }
        for (LargeDialogOptionButton button : buttons) {
            BridgeProtocol.OptionState option = new BridgeProtocol.OptionState();
            option.index = button.slot;
            option.id = "event_option_" + button.slot;
            option.label = safe(button.msg);
            option.disabled = button.isDisabled;
            state.event_options.add(option);
        }
    }

    private void populateCampfireState(BridgeProtocol.StateSnapshot state, AbstractRoom room) {
        if (room == null) {
            return;
        }
        Object campfireUiObj = ReflectionHacks.getPrivate(room, room.getClass(), "campfireUI");
        if (!(campfireUiObj instanceof CampfireUI)) {
            return;
        }
        CampfireUI campfireUI = (CampfireUI) campfireUiObj;
        @SuppressWarnings("unchecked")
        ArrayList<AbstractCampfireOption> buttons = (ArrayList<AbstractCampfireOption>) ReflectionHacks.getPrivate(campfireUI, CampfireUI.class, "buttons");
        if (buttons == null) {
            return;
        }
        for (int index = 0; index < buttons.size(); index++) {
            AbstractCampfireOption option = buttons.get(index);
            BridgeProtocol.OptionState stateOption = new BridgeProtocol.OptionState();
            stateOption.index = index;
            stateOption.id = option.getClass().getSimpleName();
            stateOption.label = safe((String) ReflectionHacks.getPrivate(option, AbstractCampfireOption.class, "label"));
            stateOption.disabled = !option.usable;
            state.campfire_options.add(stateOption);
        }
    }

    private void populateSelectionStates(BridgeProtocol.StateSnapshot state) {
        if (AbstractDungeon.handCardSelectScreen != null) {
            CardGroup hand = (CardGroup) ReflectionHacks.getPrivate(AbstractDungeon.handCardSelectScreen, HandCardSelectScreen.class, "hand");
            if (hand != null) {
                for (int index = 0; index < hand.group.size(); index++) {
                    AbstractCard card = hand.group.get(index);
                    BridgeProtocol.OptionState option = new BridgeProtocol.OptionState();
                    option.index = index;
                    option.id = card.uuid.toString();
                    option.label = card.name;
                    option.disabled = false;
                    state.hand_select_cards.add(option);
                }
            }
        }
        if (AbstractDungeon.gridSelectScreen != null && AbstractDungeon.gridSelectScreen.targetGroup != null) {
            for (int index = 0; index < AbstractDungeon.gridSelectScreen.targetGroup.group.size(); index++) {
                AbstractCard card = AbstractDungeon.gridSelectScreen.targetGroup.group.get(index);
                BridgeProtocol.OptionState option = new BridgeProtocol.OptionState();
                option.index = index;
                option.id = card.uuid.toString();
                option.label = card.name;
                option.disabled = false;
                state.grid_select_cards.add(option);
            }
        }
    }

    private void populateMapState(BridgeProtocol.StateSnapshot state) {
        BridgeProtocol.MapState mapState = new BridgeProtocol.MapState();
        MapRoomNode current = AbstractDungeon.getCurrMapNode();
        if (current != null) {
            mapState.current_x = current.x;
            mapState.current_y = current.y;
        }
        if (AbstractDungeon.map != null) {
            for (ArrayList<MapRoomNode> row : AbstractDungeon.map) {
                for (MapRoomNode node : row) {
                    if (node == null || node.room == null || !isNodeAvailable(node)) {
                        continue;
                    }
                    BridgeProtocol.MapNodeState nodeState = new BridgeProtocol.MapNodeState();
                    nodeState.x = node.x;
                    nodeState.y = node.y;
                    nodeState.symbol = node.getRoomSymbol(Boolean.TRUE);
                    nodeState.room_type = node.getRoom().getClass().getSimpleName();
                    nodeState.has_emerald_key = node.hasEmeraldKey;
                    nodeState.connected = current != null && (current.isConnectedTo(node) || current.wingedIsConnectedTo(node));
                    nodeState.taken = node.taken;
                    mapState.available_nodes.add(nodeState);
                }
            }
        }
        state.map = mapState;
    }

    private void populateAvailableActions(BridgeProtocol.StateSnapshot state) {
        state.available_actions.add(new BridgeProtocol.ActionDescriptor("ping", "Check whether the bridge is alive."));
        if ("combat".equals(state.context)) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("play_card", "Play a card from hand by card_uuid or card_index. Use target_index for single-target cards."));
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("end_turn", "End the current combat turn."));
        }
        if (!state.room_rewards.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("claim_room_reward", "Claim a room reward by reward_index."));
        }
        if (!state.card_reward_choices.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("choose_reward", "Pick a card reward by reward_index."));
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("skip_reward", "Skip the current card reward screen."));
        }
        if (!state.event_options.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("choose_event_option", "Choose an event dialog option by option_index."));
        }
        if (!state.campfire_options.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("choose_campfire_option", "Use a campfire option by option_index."));
        }
        if (state.map != null && !state.map.available_nodes.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("choose_map_node", "Pick a map node using x and y."));
        }
        if (!state.hand_select_cards.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("select_hand_card", "Select a hand card by card_uuid or card_index."));
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("confirm", "Confirm the current hand selection if required."));
        }
        if (!state.grid_select_cards.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("select_grid_card", "Select a grid card by card_uuid or card_index."));
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("confirm", "Confirm the current grid selection if required."));
        }
        state.available_actions.add(new BridgeProtocol.ActionDescriptor("proceed", "Press the proceed button when it is available."));
    }

    private BridgeProtocol.CommandResponse executeCommand(BridgeProtocol.CommandEnvelope command) {
        String action = safe(command.action);
        try {
            if ("play_card".equals(action)) {
                return success(command.id, BridgeActions.playCard(command));
            }
            if ("end_turn".equals(action)) {
                return success(command.id, BridgeActions.endTurn());
            }
            if ("proceed".equals(action)) {
                return success(command.id, BridgeActions.clickProceed());
            }
            if ("claim_room_reward".equals(action)) {
                return success(command.id, BridgeActions.claimRoomReward(command));
            }
            if ("choose_reward".equals(action)) {
                return success(command.id, BridgeActions.chooseCardReward(command));
            }
            if ("skip_reward".equals(action)) {
                return success(command.id, BridgeActions.skipCardReward());
            }
            if ("choose_event_option".equals(action)) {
                return success(command.id, BridgeActions.chooseEventOption(command));
            }
            if ("choose_campfire_option".equals(action)) {
                return success(command.id, BridgeActions.chooseCampfireOption(command));
            }
            if ("choose_map_node".equals(action)) {
                return success(command.id, BridgeActions.chooseMapNode(command));
            }
            if ("select_hand_card".equals(action)) {
                return success(command.id, BridgeActions.selectHandCard(command));
            }
            if ("select_grid_card".equals(action)) {
                return success(command.id, BridgeActions.selectGridCard(command));
            }
            if ("confirm".equals(action)) {
                return success(command.id, BridgeActions.confirmCurrentScreen());
            }
            if ("ping".equals(action)) {
                return success(command.id, "pong");
            }
            return failure(command.id, "Unsupported action: " + action);
        } catch (Exception exception) {
            this.lastError = exception.getMessage();
            return failure(command.id, exception.getMessage());
        }
    }

    private BridgeProtocol.CommandResponse success(String id, String message) {
        BridgeProtocol.CommandResponse response = new BridgeProtocol.CommandResponse();
        response.id = id;
        response.ok = true;
        response.message = message;
        return response;
    }

    private BridgeProtocol.CommandResponse failure(String id, String error) {
        BridgeProtocol.CommandResponse response = new BridgeProtocol.CommandResponse();
        response.id = id;
        response.ok = false;
        response.error = error;
        return response;
    }

    private boolean canPlayCard(AbstractCard card) {
        return card != null && isPlayerTurn() && card.canUse(AbstractDungeon.player, firstLivingMonster());
    }

    private boolean requiresTarget(AbstractCard card) {
        return card != null && card.target != null && (card.target == AbstractCard.CardTarget.ENEMY || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY);
    }

    private boolean isPlayerTurn() {
        return AbstractDungeon.getCurrRoom() != null && AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT && AbstractDungeon.actionManager != null && !AbstractDungeon.actionManager.turnHasEnded;
    }

    private int currentEnergy(EnergyManager energyManager) {
        return energyManager == null ? 0 : energyManager.energy;
    }

    private AbstractMonster firstLivingMonster() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.monsters == null || room.monsters.monsters == null) {
            return null;
        }
        for (AbstractMonster monster : room.monsters.monsters) {
            if (!monster.isDeadOrEscaped()) {
                return monster;
            }
        }
        return null;
    }

    private boolean isNodeAvailable(MapRoomNode node) {
        if (node == null || node.room == null || node.taken) {
            return false;
        }
        if (!AbstractDungeon.firstRoomChosen) {
            return node.y == 0;
        }
        MapRoomNode current = AbstractDungeon.getCurrMapNode();
        return current != null && (current.isConnectedTo(node) || current.wingedIsConnectedTo(node));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeEnum(Enum<?> value) {
        return value == null ? "" : value.name();
    }
}
