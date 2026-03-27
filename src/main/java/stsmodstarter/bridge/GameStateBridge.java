package stsmodstarter.bridge;

import basemod.ReflectionHacks;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.blights.AbstractBlight;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.EnergyManager;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.GenericEventDialog;
import com.megacrit.cardcrawl.events.RoomEventDialog;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.CampfireUI;
import com.megacrit.cardcrawl.screens.charSelect.CharacterOption;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuPanelButton;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MenuButton;
import com.megacrit.cardcrawl.screens.select.BossRelicSelectScreen;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.shop.StorePotion;
import com.megacrit.cardcrawl.shop.StoreRelic;
import com.megacrit.cardcrawl.ui.buttons.CancelButton;
import com.megacrit.cardcrawl.ui.buttons.CardSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.ConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton;
import com.megacrit.cardcrawl.ui.buttons.ProceedButton;
import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;

public class GameStateBridge {
    // State snapshots are throttled to reduce file I/O, but changed states still flush immediately.
    private static final long STATE_WRITE_INTERVAL_MS = 1000L;
    private static final int FILE_WRITE_RETRIES = 5;
    private static final long FILE_WRITE_RETRY_DELAY_MS = 20L;

    private final Json json;
    private long lastStateWriteAt;
    private String lastStateSnapshotJson;
    private String lastError;
    private String lastProcessedCommandId;

    public GameStateBridge() {
        this.json = new Json(JsonWriter.OutputType.json);
        this.json.setIgnoreUnknownFields(true);
    }

    // Command intake and pending input playback happen before the game update tick.
    public void preTick() {
        BridgePaths.ensureRuntimeDir();
        if (!AbstractDungeon.isPlayerInDungeon()) {
            BridgeActions.resetPendingActions();
        }
        processCommandIfPresent();
        BridgeActions.tickPendingActions();
    }

    public void postTick() {
        maybeWriteStateSnapshot(false);
    }

    public void tick() {
        preTick();
        postTick();
    }

    public void writeStateSnapshot() {
        maybeWriteStateSnapshot(true);
    }

    private void maybeWriteStateSnapshot(boolean forceWrite) {
        BridgeProtocol.StateSnapshot state = safeBuildState();
        String content = this.json.prettyPrint(state);
        long now = System.currentTimeMillis();
        boolean intervalElapsed = now - this.lastStateWriteAt >= STATE_WRITE_INTERVAL_MS;
        boolean stateChanged = this.lastStateSnapshotJson == null || !this.lastStateSnapshotJson.equals(content);
        if (!forceWrite && !intervalElapsed && !stateChanged) {
            return;
        }
        writeJson(BridgePaths.stateFile(), content);
        this.lastStateWriteAt = now;
        this.lastStateSnapshotJson = content;
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
                response.state = safeBuildState();
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
        response.state = safeBuildState();
        writeJson(BridgePaths.responseFile(), response);
    }

    private void writeJson(Path path, Object payload) {
        writeJson(path, this.json.prettyPrint(payload));
    }

    private void writeJson(Path path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");
        IOException lastException = null;
        for (int attempt = 0; attempt < FILE_WRITE_RETRIES; attempt++) {
            try {
                Files.createDirectories(path.getParent());
                Files.write(tempPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                try {
                    Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveException) {
                    Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    Files.deleteIfExists(tempPath);
                }
                return;
            } catch (IOException exception) {
                lastException = exception;
                try {
                    Thread.sleep(FILE_WRITE_RETRY_DELAY_MS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while writing file: " + path, interruptedException);
                }
            }
        }
        throw new RuntimeException("Failed to write file: " + path, lastException);
    }

    // Never let state export crash the game during dungeon teardown or new-run setup.
    private BridgeProtocol.StateSnapshot safeBuildState() {
        try {
            return buildState();
        } catch (Exception exception) {
            this.lastError = describeStateReadError(exception);
            BridgeActions.resetPendingActions();
            return buildTransitionSnapshot();
        }
    }

    private BridgeProtocol.StateSnapshot buildTransitionSnapshot() {
        BridgeProtocol.StateSnapshot state = new BridgeProtocol.StateSnapshot();
        state.timestamp_ms = System.currentTimeMillis();
        state.runtime_dir = BridgePaths.runtimeDir().toString();
        state.in_game = AbstractDungeon.isPlayerInDungeon() && AbstractDungeon.player != null;
        state.first_room_chosen = AbstractDungeon.firstRoomChosen;
        state.last_error = this.lastError;
        if (!state.in_game) {
            try {
                populateOutOfRunState(state);
                state.current_screen = state.main_menu_screen == null || state.main_menu_screen.isEmpty()
                        ? "NONE"
                        : state.main_menu_screen;
                state.context = detectOutOfRunContext();
                populateConfirmState(state);
                populateAvailableActions(state);
            } catch (Exception ignored) {
                state.current_screen = "NONE";
                state.context = "out_of_run";
            }
            return state;
        }

        state.main_menu_screen = "";
        state.current_screen = AbstractDungeon.screen == null ? "NONE" : AbstractDungeon.screen.name();
        state.context = "transition";
        state.act_num = AbstractDungeon.actNum;
        state.floor_num = AbstractDungeon.floorNum;
        state.room_phase = "NONE";
        state.room_type = "NONE";
        return state;
    }

    private String describeStateReadError(Exception exception) {
        if (exception == null) {
            return "State snapshot fallback";
        }
        String message = exception.getMessage();
        return message == null || message.isEmpty()
                ? "State snapshot fallback: " + exception.getClass().getSimpleName()
                : "State snapshot fallback: " + exception.getClass().getSimpleName() + ": " + message;
    }

    // Build one snapshot from the currently actionable UI surface.
    private BridgeProtocol.StateSnapshot buildState() {
        BridgeProtocol.StateSnapshot state = new BridgeProtocol.StateSnapshot();
        state.timestamp_ms = System.currentTimeMillis();
        state.runtime_dir = BridgePaths.runtimeDir().toString();
        state.in_game = AbstractDungeon.isPlayerInDungeon() && AbstractDungeon.player != null;
        state.first_room_chosen = AbstractDungeon.firstRoomChosen;
        state.last_error = this.lastError;
        if (!state.in_game) {
            // Main-menu data is exported only outside a run so stale menu buttons never leak into in-run state.
            populateOutOfRunState(state);
            state.current_screen = state.main_menu_screen == null || state.main_menu_screen.isEmpty()
                    ? "NONE"
                    : state.main_menu_screen;
            state.context = detectOutOfRunContext();
            populateConfirmState(state);
            populateAvailableActions(state);
            return state;
        }

        state.main_menu_screen = "";
        state.current_screen = AbstractDungeon.screen == null ? "NONE" : AbstractDungeon.screen.name();

        AbstractRoom room = safeGetCurrentRoom();
        state.context = room == null ? "transition" : detectContext(room);
        state.act_num = AbstractDungeon.actNum;
        state.floor_num = AbstractDungeon.floorNum;
        state.room_phase = room == null ? "NONE" : safeEnum(room.phase);
        state.room_type = room == null ? "NONE" : room.getClass().getSimpleName();
        populatePlayerState(state);
        populateMonsterState(state, room);
        populateRewardsState(state, room);
        populateCardRewardState(state);
        populateBossRewardState(state);
        populateEventState(state, room);
        populateCampfireState(state, room);
        populateSelectionStates(state);
        populateShopState(state, room);
        populateTreasureState(state, room);
        populateMapState(state, room);
        populateConfirmState(state);
        populateAvailableActions(state);
        return state;
    }

    private String detectOutOfRunContext() {
        MainMenuScreen mainMenuScreen = CardCrawlGame.mainMenuScreen;
        if (mainMenuScreen == null || mainMenuScreen.screen == null) {
            return "out_of_run";
        }
        if (mainMenuScreen.screen == MainMenuScreen.CurScreen.MAIN_MENU) {
            return "main_menu";
        }
        if (mainMenuScreen.screen == MainMenuScreen.CurScreen.CHAR_SELECT) {
            return "character_select";
        }
        if (mainMenuScreen.screen == MainMenuScreen.CurScreen.PANEL_MENU) {
            return "panel_menu";
        }
        if (mainMenuScreen.screen == MainMenuScreen.CurScreen.ABANDON_CONFIRM) {
            return "abandon_confirm";
        }
        return "out_of_run";
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
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.BOSS_REWARD) {
            return "boss_reward";
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SHOP) {
            return "shop";
        }
        // Neow can leave the map visible behind the dialog, but the event button is still the real input surface.
        if (hasActiveEventOptions(room)) {
            return "event";
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP) {
            return "map";
        }
        if (room != null && room.getClass().getSimpleName().contains("RestRoom")) {
            return "campfire";
        }
        if (room != null && room.phase == AbstractRoom.RoomPhase.COMBAT) {
            return "combat";
        }
        if (room != null && room.rewardTime) {
            return "room_rewards";
        }
        // Some events leave the map immediately usable before the room clears its event reference.
        if (hasAvailableMapNodes()) {
            return "map";
        }
        if (room != null && room.phase == AbstractRoom.RoomPhase.EVENT) {
            return "event";
        }
        return "room";
    }

    // Even outside a run we still export enough state to start a new game from the main menu.
    private void populateOutOfRunState(BridgeProtocol.StateSnapshot state) {
        MainMenuScreen mainMenuScreen = CardCrawlGame.mainMenuScreen;
        if (mainMenuScreen == null || mainMenuScreen.screen == null) {
            state.main_menu_screen = "";
            return;
        }
        state.main_menu_screen = mainMenuScreen.screen.name();
        populateMainMenuState(state, mainMenuScreen);
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
        ArrayList<RewardItem> visibleRewards = getVisibleRewards(room);
        if (visibleRewards == null) {
            return;
        }
        for (int index = 0; index < visibleRewards.size(); index++) {
            RewardItem reward = visibleRewards.get(index);
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

    private ArrayList<RewardItem> getVisibleRewards(AbstractRoom room) {
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD
                && AbstractDungeon.combatRewardScreen != null
                && AbstractDungeon.combatRewardScreen.rewards != null) {
            return AbstractDungeon.combatRewardScreen.rewards;
        }
        if (room == null || room.rewards == null) {
            return null;
        }
        return room.rewards;
    }

    private void populateCardRewardState(BridgeProtocol.StateSnapshot state) {
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.CARD_REWARD || AbstractDungeon.cardRewardScreen == null || AbstractDungeon.cardRewardScreen.rewardGroup == null) {
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

    private void populateBossRewardState(BridgeProtocol.StateSnapshot state) {
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.BOSS_REWARD || AbstractDungeon.bossRelicScreen == null) {
            return;
        }
        BossRelicSelectScreen bossRelicScreen = AbstractDungeon.bossRelicScreen;
        int optionIndex = 0;
        if (bossRelicScreen.relics != null) {
            for (AbstractRelic relic : bossRelicScreen.relics) {
                if (relic == null) {
                    continue;
                }
                BridgeProtocol.OptionState option = new BridgeProtocol.OptionState();
                option.index = optionIndex++;
                option.id = relic.relicId;
                option.label = relic.name;
                option.disabled = false;
                option.note = safe(relic.description);
                state.boss_reward_choices.add(option);
            }
        }
        if (bossRelicScreen.blights != null) {
            for (AbstractBlight blight : bossRelicScreen.blights) {
                if (blight == null) {
                    continue;
                }
                BridgeProtocol.OptionState option = new BridgeProtocol.OptionState();
                option.index = optionIndex++;
                option.id = blight.blightID;
                option.label = blight.name;
                option.disabled = false;
                option.note = safe(blight.description);
                state.boss_reward_choices.add(option);
            }
        }
    }

    private void populateEventState(BridgeProtocol.StateSnapshot state, AbstractRoom room) {
        for (LargeDialogOptionButton button : getActiveEventButtons(room)) {
            BridgeProtocol.OptionState option = new BridgeProtocol.OptionState();
            option.index = button.slot;
            option.id = "event_option_" + button.slot;
            option.label = safe(button.msg);
            option.disabled = button.isDisabled;
            state.event_options.add(option);
        }
    }
    private ArrayList<LargeDialogOptionButton> getActiveEventButtons(AbstractRoom room) {
        ArrayList<LargeDialogOptionButton> buttons = new ArrayList<LargeDialogOptionButton>();
        if (room == null || room.event == null) {
            return buttons;
        }
        if (!isEventDialogVisible(room)) {
            return buttons;
        }
        if (room.event.imageEventText != null && room.event.imageEventText.optionList != null && !room.event.imageEventText.optionList.isEmpty()) {
            buttons.addAll(room.event.imageEventText.optionList);
        } else if (RoomEventDialog.optionList != null && !RoomEventDialog.optionList.isEmpty()) {
            buttons.addAll(RoomEventDialog.optionList);
        }
        return buttons;
    }

    private boolean isEventDialogVisible(AbstractRoom room) {
        if (room == null || room.event == null) {
            return false;
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP
                && room.phase == AbstractRoom.RoomPhase.COMPLETE) {
            return false;
        }
        try {
            if (room.event.imageEventText != null) {
                Field showField = GenericEventDialog.class.getDeclaredField("show");
                showField.setAccessible(true);
                Object raw = showField.get(null);
                if (raw instanceof Boolean) {
                    return ((Boolean) raw).booleanValue();
                }
            }
            if (room.event.roomEventText != null) {
                Field showField = RoomEventDialog.class.getDeclaredField("show");
                showField.setAccessible(true);
                Object raw = showField.get(room.event.roomEventText);
                if (raw instanceof Boolean) {
                    return ((Boolean) raw).booleanValue();
                }
            }
        } catch (Exception ignored) {
        }
        return RoomEventDialog.waitForInput;
    }


    private boolean hasActiveEventOptions(AbstractRoom room) {
        return !getActiveEventButtons(room).isEmpty();
    }

    private void populateCampfireState(BridgeProtocol.StateSnapshot state, AbstractRoom room) {
        if (room == null || !room.getClass().getSimpleName().contains("RestRoom")) {
            return;
        }
        Object campfireUiObj;
        try {
            campfireUiObj = ReflectionHacks.getPrivate(room, room.getClass(), "campfireUI");
        } catch (RuntimeException exception) {
            this.lastError = exception.getMessage();
            return;
        }
        if (!(campfireUiObj instanceof CampfireUI)) {
            return;
        }
        CampfireUI campfireUI = (CampfireUI) campfireUiObj;
        @SuppressWarnings("unchecked")
        ArrayList<AbstractCampfireOption> buttons;
        try {
            buttons = (ArrayList<AbstractCampfireOption>) ReflectionHacks.getPrivate(campfireUI, CampfireUI.class, "buttons");
        } catch (RuntimeException exception) {
            this.lastError = exception.getMessage();
            return;
        }
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
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.HAND_SELECT && AbstractDungeon.handCardSelectScreen != null) {
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
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID && AbstractDungeon.gridSelectScreen != null && AbstractDungeon.gridSelectScreen.targetGroup != null) {
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
    private void populateMainMenuState(BridgeProtocol.StateSnapshot state, MainMenuScreen mainMenuScreen) {
        if (mainMenuScreen.screen == MainMenuScreen.CurScreen.MAIN_MENU && mainMenuScreen.buttons != null) {
            for (int index = 0; index < mainMenuScreen.buttons.size(); index++) {
                MenuButton button = mainMenuScreen.buttons.get(index);
                if (button == null || button.result == null) {
                    continue;
                }
                BridgeProtocol.OptionState option = new BridgeProtocol.OptionState();
                option.index = index;
                option.id = button.result.name();
                option.label = safe(readMenuButtonLabel(button));
                option.disabled = false;
                state.main_menu_options.add(option);
            }
        }
        if (mainMenuScreen.screen == MainMenuScreen.CurScreen.CHAR_SELECT && mainMenuScreen.charSelectScreen != null && mainMenuScreen.charSelectScreen.options != null) {
            for (int index = 0; index < mainMenuScreen.charSelectScreen.options.size(); index++) {
                CharacterOption character = mainMenuScreen.charSelectScreen.options.get(index);
                if (character == null) {
                    continue;
                }
                BridgeProtocol.OptionState option = new BridgeProtocol.OptionState();
                option.index = index;
                option.id = character.c == null || character.c.chosenClass == null ? "character_" + index : character.c.chosenClass.name();
                option.label = safe(character.name);
                option.disabled = character.locked;
                option.note = character.selected ? "selected" : "";
                state.character_options.add(option);
                if (character.selected) {
                    state.selected_character = option.id;
                }
            }
        }
    }

    private void populateTreasureState(BridgeProtocol.StateSnapshot state, AbstractRoom room) {
        if (room == null || !room.getClass().getSimpleName().contains("TreasureRoom")) {
            return;
        }
        Object chestObj;
        try {
            chestObj = ReflectionHacks.getPrivate(room, room.getClass(), "chest");
        } catch (RuntimeException exception) {
            this.lastError = exception.getMessage();
            return;
        }
        if (chestObj == null) {
            return;
        }
        BridgeProtocol.TreasureChestState chestState = new BridgeProtocol.TreasureChestState();
        chestState.available = true;
        try {
            chestState.is_open = (Boolean) ReflectionHacks.getPrivate(chestObj, chestObj.getClass().getSuperclass(), "isOpen");
            Object relicReward = ReflectionHacks.getPrivate(chestObj, chestObj.getClass().getSuperclass(), "relicReward");
            chestState.relic_reward = relicReward == null ? null : relicReward.toString();
            Boolean goldReward = (Boolean) ReflectionHacks.getPrivate(chestObj, chestObj.getClass().getSuperclass(), "goldReward");
            chestState.gold_reward = Boolean.TRUE.equals(goldReward);
            Boolean cursed = (Boolean) ReflectionHacks.getPrivate(chestObj, chestObj.getClass().getSuperclass(), "cursed");
            chestState.cursed = Boolean.TRUE.equals(cursed);
        } catch (RuntimeException exception) {
            this.lastError = exception.getMessage();
        }
        state.treasure_chest = chestState;
    }

    private void populateShopState(BridgeProtocol.StateSnapshot state, AbstractRoom room) {
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.SHOP || AbstractDungeon.shopScreen == null) {
            return;
        }

        ShopScreen shopScreen = AbstractDungeon.shopScreen;
        int shopCardIndex = 0;
        if (shopScreen.coloredCards != null) {
            for (AbstractCard card : shopScreen.coloredCards) {
                if (card == null) {
                    continue;
                }
                BridgeProtocol.ShopCardState shopCard = new BridgeProtocol.ShopCardState();
                shopCard.index = shopCardIndex++;
                shopCard.uuid = card.uuid.toString();
                shopCard.id = card.cardID;
                shopCard.name = card.name;
                shopCard.price = card.price;
                shopCard.affordable = AbstractDungeon.player != null && AbstractDungeon.player.gold >= card.price;
                shopCard.colorless = false;
                shopCard.description = safe(card.rawDescription);
                state.shop_cards.add(shopCard);
            }
        }
        if (shopScreen.colorlessCards != null) {
            for (AbstractCard card : shopScreen.colorlessCards) {
                if (card == null) {
                    continue;
                }
                BridgeProtocol.ShopCardState shopCard = new BridgeProtocol.ShopCardState();
                shopCard.index = shopCardIndex++;
                shopCard.uuid = card.uuid.toString();
                shopCard.id = card.cardID;
                shopCard.name = card.name;
                shopCard.price = card.price;
                shopCard.affordable = AbstractDungeon.player != null && AbstractDungeon.player.gold >= card.price;
                shopCard.colorless = true;
                shopCard.description = safe(card.rawDescription);
                state.shop_cards.add(shopCard);
            }
        }

        @SuppressWarnings("unchecked")
        ArrayList<StoreRelic> relics = (ArrayList<StoreRelic>) ReflectionHacks.getPrivate(shopScreen, ShopScreen.class, "relics");
        if (relics != null) {
            for (int index = 0; index < relics.size(); index++) {
                StoreRelic relic = relics.get(index);
                if (relic == null || relic.relic == null) {
                    continue;
                }
                BridgeProtocol.ShopItemState item = new BridgeProtocol.ShopItemState();
                item.index = index;
                item.id = relic.relic.relicId;
                item.name = relic.relic.name;
                item.price = relic.price;
                item.affordable = AbstractDungeon.player != null && AbstractDungeon.player.gold >= relic.price;
                item.purchased = relic.isPurchased;
                item.item_type = "relic";
                state.shop_relics.add(item);
            }
        }

        @SuppressWarnings("unchecked")
        ArrayList<StorePotion> potions = (ArrayList<StorePotion>) ReflectionHacks.getPrivate(shopScreen, ShopScreen.class, "potions");
        if (potions != null) {
            for (int index = 0; index < potions.size(); index++) {
                StorePotion potion = potions.get(index);
                if (potion == null || potion.potion == null) {
                    continue;
                }
                BridgeProtocol.ShopItemState item = new BridgeProtocol.ShopItemState();
                item.index = index;
                item.id = potion.potion.ID;
                item.name = potion.potion.name;
                item.price = potion.price;
                item.affordable = AbstractDungeon.player != null && AbstractDungeon.player.gold >= potion.price;
                item.purchased = potion.isPurchased;
                item.item_type = "potion";
                state.shop_potions.add(item);
            }
        }

        BridgeProtocol.ShopPurgeState purgeState = new BridgeProtocol.ShopPurgeState();
        purgeState.available = shopScreen.purgeAvailable;
        purgeState.price = ShopScreen.actualPurgeCost;
        purgeState.affordable = AbstractDungeon.player != null && AbstractDungeon.player.gold >= ShopScreen.actualPurgeCost;
        state.shop_purge = purgeState;
    }

    private void populateMapState(BridgeProtocol.StateSnapshot state, AbstractRoom room) {
        // When an event dialog is still in front, the map is only background and should not be exposed.
        if (hasActiveEventOptions(room)) {
            return;
        }
        BridgeProtocol.MapState mapState = new BridgeProtocol.MapState();
        MapRoomNode current = AbstractDungeon.getCurrMapNode();
        if (current != null) {
            mapState.current_x = current.x;
            mapState.current_y = current.y;
        }
        if (AbstractDungeon.map != null) {
            for (ArrayList<MapRoomNode> row : AbstractDungeon.map) {
                for (MapRoomNode node : row) {
                    if (node == null || node.room == null) {
                        continue;
                    }
                    BridgeProtocol.MapNodeState nodeState = buildMapNodeState(node, current);
                    mapState.all_nodes.add(nodeState);
                    if (nodeState.reachable_now) {
                        mapState.available_nodes.add(nodeState);
                    }
                }
            }
        }
        state.map = mapState;
    }

    private BridgeProtocol.MapNodeState buildMapNodeState(MapRoomNode node, MapRoomNode current) {
        BridgeProtocol.MapNodeState nodeState = new BridgeProtocol.MapNodeState();
        nodeState.x = node.x;
        nodeState.y = node.y;
        nodeState.symbol = node.getRoomSymbol(Boolean.TRUE);
        nodeState.room_type = node.getRoom().getClass().getSimpleName();
        nodeState.has_emerald_key = node.hasEmeraldKey;
        nodeState.connected = current != null && (current.isConnectedTo(node) || current.wingedIsConnectedTo(node));
        nodeState.reachable_now = isNodeAvailable(node);
        nodeState.taken = node.taken;
        for (MapRoomNode parent : node.getParents()) {
            if (parent != null) {
                nodeState.parents.add(parent.x + "," + parent.y);
            }
        }
        if (AbstractDungeon.map != null && node.y + 1 >= 0 && node.y + 1 < AbstractDungeon.map.size()) {
            ArrayList<MapRoomNode> nextRow = AbstractDungeon.map.get(node.y + 1);
            for (MapRoomNode candidate : nextRow) {
                if (candidate != null && (node.isConnectedTo(candidate) || node.wingedIsConnectedTo(candidate))) {
                    nodeState.children.add(candidate.x + "," + candidate.y);
                }
            }
        }
        return nodeState;
    }
    private void populateConfirmState(BridgeProtocol.StateSnapshot state) {
        if (!state.in_game) {
            MainMenuScreen mainMenuScreen = CardCrawlGame.mainMenuScreen;
            if (mainMenuScreen != null
                    && mainMenuScreen.screen == MainMenuScreen.CurScreen.CHAR_SELECT
                    && mainMenuScreen.charSelectScreen != null) {
                ConfirmButton button = mainMenuScreen.charSelectScreen.confirmButton;
                state.confirm_available = button != null;
                state.confirm_enabled = button != null && !button.isDisabled;
                state.confirm_context = "character_select";
            }
            if (mainMenuScreen != null
                    && mainMenuScreen.screen == MainMenuScreen.CurScreen.ABANDON_CONFIRM
                    && mainMenuScreen.abandonPopup != null
                    && mainMenuScreen.abandonPopup.shown) {
                state.confirm_available = mainMenuScreen.abandonPopup.yesHb != null;
                state.confirm_enabled = mainMenuScreen.abandonPopup.yesHb != null;
                state.confirm_context = "abandon_confirm";
                state.cancel_available = mainMenuScreen.abandonPopup.noHb != null;
                state.cancel_enabled = mainMenuScreen.abandonPopup.noHb != null;
                state.cancel_context = "abandon_confirm";
            }
            if (mainMenuScreen != null
                    && mainMenuScreen.screen == MainMenuScreen.CurScreen.PANEL_MENU
                    && mainMenuScreen.panelScreen != null
                    && mainMenuScreen.panelScreen.button != null
                    && !mainMenuScreen.panelScreen.button.isHidden) {
                state.cancel_available = true;
                state.cancel_enabled = true;
                state.cancel_context = "panel_menu";
            }
            return;
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.HAND_SELECT) {
            CardSelectConfirmButton button = AbstractDungeon.handCardSelectScreen == null ? null : AbstractDungeon.handCardSelectScreen.button;
            state.confirm_available = button != null;
            state.confirm_enabled = button != null && !button.isDisabled;
            state.confirm_context = "hand_select";
            return;
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID) {
            GridSelectConfirmButton button = AbstractDungeon.gridSelectScreen == null ? null : AbstractDungeon.gridSelectScreen.confirmButton;
            state.confirm_available = button != null;
            state.confirm_enabled = button != null && !button.isDisabled;
            state.confirm_context = "grid_select";
            return;
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.BOSS_REWARD && AbstractDungeon.bossRelicScreen != null) {
            ConfirmButton button = AbstractDungeon.bossRelicScreen.confirmButton;
            state.confirm_available = button != null;
            state.confirm_enabled = button != null && !button.isDisabled;
            state.confirm_context = "boss_reward";
            return;
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SHOP && AbstractDungeon.overlayMenu != null && AbstractDungeon.overlayMenu.cancelButton != null) {
            CancelButton button = AbstractDungeon.overlayMenu.cancelButton;
            state.cancel_available = !button.isHidden;
            state.cancel_enabled = !button.isHidden;
            state.cancel_context = "shop";
            return;
        }
        CampfireUI campfireUI = tryGetCampfireUi();
        if (campfireUI != null) {
            ConfirmButton button = campfireUI.confirmButton;
            state.confirm_available = button != null;
            state.confirm_enabled = button != null && !button.isDisabled;
            state.confirm_context = "campfire";
        }
    }

    private boolean hasClaimableRoomReward(BridgeProtocol.StateSnapshot state) {
        for (BridgeProtocol.RewardState reward : state.room_rewards) {
            if (reward != null && reward.claimable) {
                return true;
            }
        }
        return false;
    }
    private void populateAvailableActions(BridgeProtocol.StateSnapshot state) {
        state.available_actions.add(new BridgeProtocol.ActionDescriptor("ping", "Check whether the bridge is alive."));
        if (!state.main_menu_options.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("choose_main_menu_option", "Click a main menu button by option_index."));
        }
        if (!state.character_options.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("select_character", "Select a character by option_index."));
        }
        if ("combat".equals(state.context) && isPlayerTurn()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("play_card", "Play a card from hand by card_uuid or card_index. Use target_index for single-target cards."));
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("end_turn", "End the current combat turn."));
            if (hasUsablePotion()) {
                state.available_actions.add(new BridgeProtocol.ActionDescriptor("use_potion", "Use a combat potion by option_index. Use target_index for targeted potions."));
            }
        }
        if ("room_rewards".equals(state.context) && hasClaimableRoomReward(state)) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("claim_room_reward", "Claim a room reward by reward_index."));
        }
        if ("card_reward".equals(state.context) && !state.card_reward_choices.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("choose_reward", "Pick a card reward by reward_index."));
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("skip_reward", "Skip the current card reward screen."));
        }
        if (!state.boss_reward_choices.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("choose_boss_reward", "Choose a boss reward by option_index, then call confirm if needed."));
        }
        if (!state.event_options.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("choose_event_option", "Choose an event dialog option by option_index."));
        }
        if (!state.campfire_options.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("choose_campfire_option", "Use a campfire option by option_index."));
        }
        if (state.room_type != null && state.room_type.contains("TreasureRoom") && state.treasure_chest != null && !state.treasure_chest.is_open) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("open_treasure_chest", "Click the treasure chest."));
        }
        if (state.room_type != null && state.room_type.contains("ShopRoom") && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.SHOP) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("open_shop", "Click the merchant to open the shop screen."));
        }
        if ("shop".equals(state.context) && !state.shop_cards.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("buy_shop_card", "Buy a shop card by card_index."));
        }
        if ("shop".equals(state.context) && !state.shop_relics.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("buy_shop_relic", "Buy a shop relic by option_index."));
        }
        if ("shop".equals(state.context) && !state.shop_potions.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("buy_shop_potion", "Buy a shop potion by option_index."));
        }
        if ("shop".equals(state.context) && state.shop_purge != null && state.shop_purge.available) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("buy_shop_purge", "Buy the shop purge service and open card removal."));
        }
        if ("map".equals(state.context) && state.map != null && !state.map.available_nodes.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("choose_map_node", "Pick a map node using x and y."));
        }
        if (!state.hand_select_cards.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("select_hand_card", "Select a hand card by card_uuid or card_index."));
        }
        if (!state.grid_select_cards.isEmpty()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("select_grid_card", "Select a grid card by card_uuid or card_index."));
        }
        if (state.confirm_available) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("confirm", "Confirm the current selection when the confirm button is enabled."));
        }
        if (state.cancel_available) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("cancel", "Cancel the current selection or dialog when the cancel button is enabled."));
        }
        if (state.in_game && canUseProceedButton()) {
            state.available_actions.add(new BridgeProtocol.ActionDescriptor("proceed", "Press the proceed button when it is available."));
        }
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
            if ("use_potion".equals(action)) {
                return success(command.id, BridgeActions.usePotion(command));
            }
            if ("proceed".equals(action)) {
                return success(command.id, BridgeActions.clickProceed());
            }
            if ("choose_main_menu_option".equals(action)) {
                return success(command.id, BridgeActions.chooseMainMenuOption(command));
            }
            if ("select_character".equals(action)) {
                return success(command.id, BridgeActions.selectCharacter(command));
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
            if ("choose_boss_reward".equals(action)) {
                return success(command.id, BridgeActions.chooseBossReward(command));
            }
            if ("choose_event_option".equals(action)) {
                return success(command.id, BridgeActions.chooseEventOption(command));
            }
            if ("choose_campfire_option".equals(action)) {
                return success(command.id, BridgeActions.chooseCampfireOption(command));
            }
            if ("open_treasure_chest".equals(action)) {
                return success(command.id, BridgeActions.openTreasureChest());
            }
            if ("open_shop".equals(action)) {
                return success(command.id, BridgeActions.openShop());
            }
            if ("buy_shop_card".equals(action)) {
                return success(command.id, BridgeActions.buyShopCard(command));
            }
            if ("buy_shop_relic".equals(action)) {
                return success(command.id, BridgeActions.buyShopRelic(command));
            }
            if ("buy_shop_potion".equals(action)) {
                return success(command.id, BridgeActions.buyShopPotion(command));
            }
            if ("buy_shop_purge".equals(action)) {
                return success(command.id, BridgeActions.buyShopPurge());
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
            if ("cancel".equals(action)) {
                return success(command.id, BridgeActions.cancelCurrentScreen());
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
        AbstractRoom room = safeGetCurrentRoom();
        return room != null && room.phase == AbstractRoom.RoomPhase.COMBAT && AbstractDungeon.actionManager != null && !AbstractDungeon.actionManager.turnHasEnded;
    }

    private boolean hasUsablePotion() {
        if (AbstractDungeon.player == null || AbstractDungeon.player.potions == null) {
            return false;
        }
        for (AbstractPotion potion : AbstractDungeon.player.potions) {
            if (potion != null && potion.canUse()) {
                return true;
            }
        }
        return false;
    }

    private boolean canUseProceedButton() {
        ProceedButton button = AbstractDungeon.overlayMenu == null ? null : AbstractDungeon.overlayMenu.proceedButton;
        if (button == null) {
            return false;
        }
        try {
            Boolean isHidden = (Boolean) ReflectionHacks.getPrivate(button, ProceedButton.class, "isHidden");
            if (Boolean.TRUE.equals(isHidden)) {
                return false;
            }
        } catch (RuntimeException ignored) {
        }
        try {
            Hitbox hitbox = (Hitbox) ReflectionHacks.getPrivate(button, ProceedButton.class, "hb");
            return hitbox != null;
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private int currentEnergy(EnergyManager energyManager) {
        return energyManager == null ? 0 : energyManager.energy;
    }

    private AbstractMonster firstLivingMonster() {
        AbstractRoom room = safeGetCurrentRoom();
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

    private CampfireUI tryGetCampfireUi() {
        AbstractRoom room = safeGetCurrentRoom();
        if (room == null || !room.getClass().getSimpleName().contains("RestRoom")) {
            return null;
        }
        Object campfireUiObj;
        try {
            campfireUiObj = ReflectionHacks.getPrivate(room, room.getClass(), "campfireUI");
        } catch (RuntimeException exception) {
            return null;
        }
        return campfireUiObj instanceof CampfireUI ? (CampfireUI) campfireUiObj : null;
    }

    private AbstractRoom safeGetCurrentRoom() {
        try {
            return AbstractDungeon.getCurrRoom();
        } catch (RuntimeException ignored) {
            return null;
        }
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

    private boolean hasAvailableMapNodes() {
        if (AbstractDungeon.map == null) {
            return false;
        }
        for (ArrayList<MapRoomNode> row : AbstractDungeon.map) {
            for (MapRoomNode node : row) {
                if (isNodeAvailable(node)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String readMenuButtonLabel(MenuButton button) {
        try {
            Object label = ReflectionHacks.getPrivate(button, MenuButton.class, "label");
            if (label instanceof String) {
                return (String) label;
            }
        } catch (RuntimeException ignored) {
        }
        return button.result == null ? "" : button.result.name();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeEnum(Enum<?> value) {
        return value == null ? "" : value.name();
    }
}













