// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.keymap;

import com.intellij.execution.ExecutorRegistry;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.keymap.ex.KeymapManagerEx;
import com.intellij.openapi.keymap.impl.KeymapImpl;
import com.intellij.openapi.keymap.impl.MacOSDefaultKeymap;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.testFramework.fixtures.BareTestFixtureTestCase;
import com.intellij.ui.KeyStrokeAdapter;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;

import javax.swing.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.stream.Collectors;

import static com.intellij.testFramework.assertions.Assertions.assertThat;
import static org.junit.Assert.*;

public abstract class KeymapsTestCaseBase extends BareTestFixtureTestCase {
  private static final int KEY_LENGTH = 24;
  private static final Set<String> LINUX_KEYMAPS = Set.of("Default for XWin", "Default for GNOME", "Default for KDE");

  protected static final String SECOND_STROKE = "SECOND_STROKE_SHORTCUT";
  protected static final String NO_DUPLICATES = "<no duplicates>";

  /**
   * @return Keymap -> Shortcut -> [ActionId]
   */
  protected abstract Map<String, Map<String, List<String>>> getKnownDuplicates();

  protected abstract Set<String> getUnknownActions();

  protected abstract Set<String> getBoundActions();

  protected abstract Collection<String> getConflictSafeGroups();

  protected abstract String getGroupForUnknownAction(@NotNull String actionId);

  protected static Map<String, Map<String, List<String>>> parseDuplicates(Map<String, String[][]> duplicates) {
    Map<String, Map<String, List<String>>> result = new HashMap<>();

    for (Map.Entry<String, String[][]> eachKeymap : duplicates.entrySet()) {
      String keymapName = eachKeymap.getKey();
      Map<String, List<String>> mapping = result.computeIfAbsent(keymapName, k -> new LinkedHashMap<>());

      for (String[] values : eachKeymap.getValue()) {
        assertTrue("known duplicates list entry for '" + keymapName + "' must not contain empty array",
                   values.length > 0);
        assertTrue("known duplicates list entry for '" + keymapName + "', shortcut '" + values[0] + "' must contain at least two conflicting action ids",
                   values.length > 2 || values.length == 2 && NO_DUPLICATES.equals(values[1]));
        assertFalse("known duplicates list entry for '" + keymapName + "', shortcut '" + values[0] + "' must not contain duplicated shortcuts",
                    mapping.containsKey(values[0]));

        List<String> actions = new ArrayList<>(List.of(values));
        actions.remove(0); // shortcut
        actions.remove(NO_DUPLICATES);
        mapping.put(values[0], actions);
      }
    }

    return result;
  }

  /**
   * Drops records from baseDuplicates for shortcuts, that are registered in non-included plugin.xml
   */
  protected static Map<String, Map<String, List<String>>> amendDuplicates(Map<String, Map<String, List<String>>> baseMapping,
                                                                          Map<String, String[][]> ideDuplicates) {
    Map<String, Map<String, List<String>>> ideMapping = parseDuplicates(ideDuplicates);
    Map<String, Map<String, List<String>>> result = new HashMap<>();

    KeymapManagerEx km = KeymapManagerEx.getInstanceEx();
    ActionManager am = ActionManager.getInstance();

    for (Keymap keymap : km.getAllKeymaps()) {
      String keymapName = keymap.getName();
      assertSame(keymap, km.getKeymap(keymapName));

      Map<String, List<String>> map = result.computeIfAbsent(keymapName, key -> new HashMap<>());
      Map<String, List<String>> baseMap = baseMapping.get(keymapName);
      if (baseMap == null) continue;

      for (String shortcut : baseMap.keySet()) {
        List<String> actionIds = ContainerUtil.filter(
          baseMap.get(shortcut),
          actionId -> SECOND_STROKE.equals(actionId) || am.getAction(actionId) != null || keymap.getShortcuts(actionId).length > 0);
        if (actionIds.size() >= 2) {
          map.put(shortcut, actionIds);
        }
      }
    }

    for (String keymap : ideMapping.keySet()) {
      Map<String, List<String>> map = result.computeIfAbsent(keymap, key -> new HashMap<>());
      Map<String, List<String>> ideMap = ideMapping.get(keymap);
      map.putAll(ideMap);
    }

    return result;
  }


  @Before
  public void setUp() {
    ExecutorRegistry.getInstance();
  }


  @Test
  public void testUnknownActionIds() {
    StringBuilder failMessage = new StringBuilder();

    Set<String> unknownActions = getUnknownActions();

    MultiMap<String, String> missingActions = new MultiMap<>();

    for (Keymap keymap : KeymapManagerEx.getInstanceEx().getAllKeymaps()) {
      Collection<String> ids = keymap.getActionIdList();
      assertThat(ids).doesNotHaveDuplicates();

      for (String cid : ids) {
        if (unknownActions.contains(cid)) continue;

        AnAction action = ActionManager.getInstance().getAction(cid);
        if (action == null) {
          missingActions.putValue(keymap.getName(), cid);
        }
      }
    }

    List<String> reappearedAction = new ArrayList<>();
    for (String id : unknownActions) {
      AnAction action = ActionManager.getInstance().getAction(id);
      if (action != null) {
        reappearedAction.add(id);
      }
    }

    if (!missingActions.isEmpty()) {
      for (String keymap : missingActions.keySet()) {
        failMessage.append("Unknown actions in keymap ").append(keymap).append(", add them to unknown actions list:\n");
        for (String action : missingActions.get(keymap)) {
          failMessage.append("\"").append(action).append("\",").append("\n");
        }
      }
    }

    if (!reappearedAction.isEmpty()) {
      failMessage.append("The following actions have reappeared, remove them from unknown action list:\n");
      for (String action : reappearedAction) {
        failMessage.append(action).append("\n");
      }
    }

    if (failMessage.length() > 0) {
      fail("\n" + failMessage);
    }
  }


  @Test
  public void testDuplicateShortcuts() {
    StringBuilder failMessage = new StringBuilder();

    Map<String, Map<Shortcut, List<String>>> expectedDuplicates = collectExpectedDuplicatedShortcuts();
    Map<String, Map<Shortcut, List<String>>> actualDuplicates = collectActualDuplicatedShortcuts();

    Set<String> allKeymaps = ContainerUtil.union(expectedDuplicates.keySet(), actualDuplicates.keySet());
    Collection<String> newKeymaps = ContainerUtil.subtract(allKeymaps, expectedDuplicates.keySet());
    Collection<String> missingKeymaps = ContainerUtil.subtract(allKeymaps, actualDuplicates.keySet());

    assertThat(newKeymaps)
      .overridingErrorMessage("Modify 'known duplicates list' test data. Keymaps were added: %s", newKeymaps)
      .isEmpty();
    assertThat(newKeymaps)
      .overridingErrorMessage("Modify 'known duplicates list' test data. Keymaps were removed: %s", missingKeymaps)
      .isEmpty();

    for (String keymap : ContainerUtil.sorted(allKeymaps)) {
      Map<Shortcut, List<String>> actual = ContainerUtil.notNullize(actualDuplicates.get(keymap));
      Map<Shortcut, List<String>> expected = ContainerUtil.notNullize(expectedDuplicates.get(keymap));

      StringBuilder keymapFailure = new StringBuilder();
      for (Shortcut shortcut : ContainerUtil.union(actual.keySet(), expected.keySet())) {
        List<String> expectedActions = ContainerUtil.notNullize(expected.get(shortcut));
        List<String> actualActions = ContainerUtil.notNullize(actual.get(shortcut));
        if (!Comparing.haveEqualElements(expectedActions, actualActions)) {
          String key = getText(shortcut);
          keymapFailure
            .append("      {\"").append(key).append('"').append(", ").append(" ".repeat(Math.max(0, KEY_LENGTH - key.length())))
            .append(actualActions.isEmpty() ? "NO_DUPLICATES" : actualActions.stream().sorted().map(a -> '"' + a + '"').collect(Collectors.joining(", ")))
            .append("},\n");
        }
      }

      if (keymapFailure.length() > 0) {
        failMessage.append(String.format("Shortcut conflicts found in keymap '%s':\n", keymap)).append(keymapFailure).append("\n");
      }
    }

    if (failMessage.length() > 0) {
      fail(failMessage +
           "\n" +
           "Please specify 'use-shortcut-of' attribute for your action if it is similar to another action (but it won't appear in Settings/Keymap),\n" +
           "reassign shortcut, or, if absolutely must, modify the 'known duplicates list'");
    }
  }

  private Map<String, Map<Shortcut, List<String>>> collectExpectedDuplicatedShortcuts() {
    Map<String, Map<String, List<String>>> knownDuplicates = getKnownDuplicates();

    Map<String, Map<Shortcut, List<String>>> expectedDuplicates = new HashMap<>();
    for (String keymap : knownDuplicates.keySet()) {
      Map<Shortcut, List<String>> keyDuplicates = new HashMap<>();
      expectedDuplicates.put(keymap, keyDuplicates);

      Map<String, List<String>> duplicates = knownDuplicates.get(keymap);
      for (String shortcut : duplicates.keySet()) {
        Shortcut keyboardShortcut = parseShortcut(shortcut);
        List<String> actions = duplicates.computeIfAbsent(shortcut, key -> new ArrayList<>());
        keyDuplicates.put(keyboardShortcut, actions);
      }
    }
    return expectedDuplicates;
  }

  private Map<String, Map<Shortcut, List<String>>> collectActualDuplicatedShortcuts() {
    Map<String, Map<Shortcut, List<String>>> result = new HashMap<>();

    KeymapManagerEx km = KeymapManagerEx.getInstanceEx();
    Set<String> boundActions = km.getBoundActions();
    Keymap[] keymaps = km.getAllKeymaps();

    // fill shortcuts
    for (Keymap keymap : keymaps) {
      Map<Shortcut, List<String>> map = new HashMap<>();
      result.put(keymap.getName(), map);

      for (String actionId : keymap.getActionIds()) {
        if (boundActions.contains(actionId)) continue;
        if (isConflictSafeAction(actionId)) continue;

        for (Shortcut shortcut : keymap.getShortcuts(actionId)) {
          List<String> actionList = map.computeIfAbsent(shortcut, key -> new ArrayList<>());
          actionList.add(actionId);

          if (shortcut instanceof KeyboardShortcut && ((KeyboardShortcut)shortcut).getSecondKeyStroke() != null) {
            KeyboardShortcut firstStroke = new KeyboardShortcut(((KeyboardShortcut)shortcut).getFirstKeyStroke(), null);
            List<String> firstStrokeActionList = map.computeIfAbsent(firstStroke, key -> new ArrayList<>());
            if (!firstStrokeActionList.contains(SECOND_STROKE)) {
              firstStrokeActionList.add(SECOND_STROKE);
            }
          }
        }
      }
    }

    if (SystemInfo.isXWindow) {
      // hack: add hardcoded shortcut from DefaultKeymapImpl to make keymaps identical under all OS
      Map<Shortcut, List<String>> defaultKeymap = result.get(KeymapManager.DEFAULT_IDEA_KEYMAP);
      List<String> actionList = defaultKeymap.computeIfAbsent(new MouseShortcut(MouseEvent.BUTTON2, 0, 1), key -> new ArrayList<>());
      actionList.add(IdeActions.ACTION_GOTO_DECLARATION);
    }

    // remove shortcuts, reused from parent keymap
    for (Keymap keymap : keymaps) {
      Map<Shortcut, List<String>> map = result.get(keymap.getName());

      List<Shortcut> reusedShortcuts = new ArrayList<>();

      for (Shortcut key : map.keySet()) {
        Shortcut parentKey = convertShortcutForParent(key, keymap);
        Keymap parent = keymap.getParent();

        while (parent != null) {
          Map<Shortcut, List<String>> parentMap = result.get(parent.getName());

          List<String> shortcut = map.get(key);
          List<String> parentShortcut = parentMap.get(parentKey);
          if (parentShortcut != null && parentShortcut.containsAll(shortcut)) {
            reusedShortcuts.add(key);
            break;
          }

          parentKey = convertShortcutForParent(parentKey, parent);
          parent = parent.getParent();
        }
      }

      for (Shortcut shortcut : reusedShortcuts) {
        map.remove(shortcut);
      }
    }

    // remove non-duplicated shortcuts
    for (Keymap keymap : keymaps) {
      Map<Shortcut, List<String>> map = result.get(keymap.getName());

      List<Shortcut> nonDuplicates = new ArrayList<>();
      for (Map.Entry<Shortcut, List<String>> entry : map.entrySet()) {
        if (entry.getValue().size() < 2) nonDuplicates.add(entry.getKey());
      }

      for (Shortcut key : nonDuplicates) {
        map.remove(key);
      }
    }

    return result;
  }

  private boolean isConflictSafeAction(String actionId) {
    Collection<String> ids = ((ActionManagerImpl)ActionManager.getInstance()).getParentGroupIds(actionId);
    for (String groupId : ids) {
      if (getConflictSafeGroups().contains(groupId) || isConflictSafeAction(groupId)) {
        return true;
      }
    }
    if (ids.isEmpty()) {
      String group = getGroupForUnknownAction(actionId);
      if (group != null && getConflictSafeGroups().contains(group)) {
        return true;
      }
    }
    return false;
  }

  private static Shortcut parseShortcut(String s) {
    if (s.equals("Force touch")) {
      return KeymapUtil.parseMouseShortcut(s);
    }
    else if (s.contains("button")) {
      return KeymapUtil.parseMouseShortcut(s);
    }
    else {
      String[] sc = s.split(",");
      assert sc.length <= 2 : s;
      KeyStroke fst = KeyStrokeAdapter.getKeyStroke(sc[0]);
      assert fst != null : s;
      KeyStroke snd = null;
      if (sc.length == 2) {
        snd = KeyStrokeAdapter.getKeyStroke(sc[1]);
      }
      return new KeyboardShortcut(fst, snd);
    }
  }

  private static String getText(Shortcut shortcut) {
    if (shortcut instanceof MouseShortcut) {
      return KeymapUtil.getMouseShortcutString((MouseShortcut)shortcut);
    }
    else if (shortcut instanceof KeyboardShortcut) {
      KeyStroke fst = ((KeyboardShortcut)shortcut).getFirstKeyStroke();
      String s = getText(fst);
      KeyStroke snd = ((KeyboardShortcut)shortcut).getSecondKeyStroke();
      if (snd != null) {
        s += ',' + getText(snd);
      }
      return s;
    }
    else {
      return KeymapUtil.getShortcutText(shortcut);
    }
  }

  private static String getText(KeyStroke fst) {
    String text = KeyStrokeAdapter.toString(fst);
    int offset = text.lastIndexOf(' ');
    if (offset == -1) offset = 0;
    return text.substring(0, offset) + StringUtil.toUpperCase(text.substring(offset));
  }

  private static Shortcut convertShortcutForParent(Shortcut key, Keymap keymap) {
    return keymap.getName().startsWith(KeymapManager.MAC_OS_X_KEYMAP) ? MacOSDefaultKeymap.convertShortcutFromParent(key) : key;
  }


  @Test
  public void testLinuxShortcuts() {
    for (Keymap keymap : KeymapManagerEx.getInstanceEx().getAllKeymaps()) {
      if (LINUX_KEYMAPS.contains(keymap.getName())) {
        checkLinuxKeymap(keymap);
      }
    }
  }

  private static void checkLinuxKeymap(Keymap keymap) {
    for (String actionId : keymap.getActionIdList()) {
      for (Shortcut shortcut : keymap.getShortcuts(actionId)) {
        if (shortcut instanceof KeyboardShortcut) {
          checkCtrlAltFn(keymap, shortcut, ((KeyboardShortcut)shortcut).getFirstKeyStroke());
          checkCtrlAltFn(keymap, shortcut, ((KeyboardShortcut)shortcut).getSecondKeyStroke());
        }
      }
    }
  }

  @SuppressWarnings("deprecation")
  private static void checkCtrlAltFn(Keymap keymap, Shortcut shortcut, KeyStroke stroke) {
    if (stroke != null) {
      int modifiers = stroke.getModifiers();
      int keyCode = stroke.getKeyCode();
      if (KeyEvent.VK_F1 <= keyCode && keyCode <= KeyEvent.VK_F12 &&
          (modifiers & InputEvent.CTRL_MASK) != 0 && (modifiers & InputEvent.ALT_MASK) != 0 && (modifiers & InputEvent.SHIFT_MASK) == 0) {
        String message = "Invalid shortcut '" + shortcut + "' for action(s) " + List.of(keymap.getActionIds(shortcut)) +
                         " in keymap '" + keymap.getName() + "' " +
                         "(Ctrl-Alt-Fn shortcuts switch Linux virtual terminals (causes newbie panic), " +
                         "so either assign another shortcut, or remove it; see Keymap_XWin.xml for reference).";
        fail(message);
      }
    }
  }


  @Test
  public void testBoundActions() {
    StringBuilder failMessage = new StringBuilder();

    Set<String> knownBoundActions = getBoundActions();

    Keymap[] keymaps = KeymapManagerEx.getInstanceEx().getAllKeymaps();
    for (Keymap keymap : keymaps) {
      KeymapImpl keymapImpl = (KeymapImpl)keymap;
      List<String> unboundActionsWithShortcut = new ArrayList<>();

      for (String actionId : keymapImpl.getActionIds()) {
        if (knownBoundActions.contains(actionId)) continue;
        boolean isBound = keymapImpl.isActionBound(actionId);
        if (isBound) {
          unboundActionsWithShortcut.add(actionId);
        }
      }

      if (!unboundActionsWithShortcut.isEmpty()) {
        failMessage.append(String.format("Shortcut for bound action found in keymap '%s':\n", keymap));
        for (String action : unboundActionsWithShortcut) {
          failMessage.append("     \"").append(action).append("\"\n");
        }
        failMessage.append("\n");
      }
    }

    if (failMessage.length() > 0) {
      fail(failMessage + "\nPlease remove these actions from keymaps, or add to the 'known bound actions' list");
    }
  }
}
