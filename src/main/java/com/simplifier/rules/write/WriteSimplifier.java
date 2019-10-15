package com.simplifier.rules.write;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class consists of methods that operate on the log:
 * check if the log contains redundant write actions, and if so,
 * remove them and return a new log.
 * <br><br>
 * <h2>List of "Write" actions:</h2>
 * <ul>
 *     <li>
 *         Excel, editCell
 *     </li>
 *     <li>
 *         Excel, paste
 *     </li>
 *     <li>
 *         Chrome, paste
 *     </li>
 *     <li>
 *         Chrome, editField
 *     </li>
 * </ul>
 * <br>
 * <h2>Redundant cases:</h2>
 * <ul>
 *     <li>
 *         <h3>
 *             Redundant "editCell" action.
 *         </h3>
 *         If there are two "editCell" actions with the same target id
 *         (cell address) and there is any number of actions between them,
 *         except "getCell" action and "copyCell" action that have the same
 *         cell address, the first "editCell" action is redundant.
 *         <br>
 *         Example:
 *         <pre>
 *             <mark style="background-color: #FF7B62">"Excel", "editCell", "C1", "1"</mark>
 *             "Excel", "getCell", "D11", "2"
 *             <mark>"Excel", "editCell", "C1", "2"</mark>
 *         </pre>
 *         <br>
 *         Second action "getCell" has cell address equals to "D11",
 *         when both "editCell" actions have cell address equals to "C1",
 *         it means that the first "editCell" action is redundant.
 *     </li>
 *     <li>
 *         <h3>
 *             Redundant "pasteIntoCell" action.
 *         </h3>
 *         If there are two "pasteIntoCell" actions with the same workbook name,
 *         sheet name and target id (cell address) and any number of actions
 *         between them except "copyCell" action with the same workbook name,
 *         sheet name and cell address, the first "pasteIntoCell" action is
 *         redundant.
 *         <br>
 *         Example:
 *         <pre>
 *             <mark style="background-color: #FF7B62">"Excel", "pasteIntoCell", "Book1", "Sheet1", "A1", "1"</mark>
 *             "Excel", "getCell", "Book1", "Sheet1", "D11", "2"
 *             <mark>"Excel", "pasteIntoCell", "Book1", "Sheet1", "A1", "1"</mark>
 *         </pre>
 *         <br>
 *         Second "pasteIntoCell" action has the same workbook name, sheet name and cell address
 *         and there is no "copyCell" action with the same workbook name, sheet name and cell
 *         address between these two "pasteIntoCell" actions, so the first "pasteIntoCell" action is
 *         redundant.
 *     </li>
 * </ul>
 */
public class WriteSimplifier {

    /**
     * This is regular expression that corresponds to the case when
     * there is any number of actions except "geCell" action and
     * "copyCell" action between "editCell" action and another
     * "editCell" action with the same target id (cell address).
     */
    private static String redundantEditCellRegex = ".*\"editCell\",(((?!,).)*,){4}(((?!,).)*),.*\\n" +
                                                   "(((((?!,).)*,){3}((?!(\"getCell\"|\"copyCell\"),(((?!,).)*,){4}\\3).)*\\n)*" +
                                                   ".*\"editCell\",(((?!,).)*,){4}\\3,.*\\n*)";

    /**
     * This is regular expression that corresponds to the case when
     * there are two "pasteIntoCell" actions with the same workbook name,
     * sheet name and target id (cell address) and any number of actions
     * between them except "copyCell" action with the same workbook name,
     * sheet name and cell address.
     */
    private static String redundantPasteIntoCellRegex = ".*\"pasteIntoCell\",(((?!,).)*,){2}((((?!,).)*,){3}).*\\n" +
                                                        "(((((?!,).)*,){3}((?!\"copyCell\",(((?!,).)*,){2}\\3).)*\\n)*" +
                                                        ".*\"pasteIntoCell\",(((?!,).)*,){2}\\3.*\\n*)";

    //Fixed? Investigate.
    private static String chromeDoublePasteRegex = ".*\"paste\",(((?!,).)*,)(((?!,).)*,)(((?!,).)*,){6}(((?!,).)*,)(((?!,).)*,).*\\n" +
                                                   "(((((?!,).)*,){3}((?!\"copy\",(((?!,).)*,){8}\\7).)*\\n)*" +
                                                   ".*\"paste\",(((?!,).)*,)\\3(((?!,).)*,){6}\\7\\9.*\\n)";

    private static String chromePasteRegex = ".*paste,(((?!,).)*,)(((?!,).)*),(((?!,).)*,){6}(((?!,).)*,)(((?!,).)*),.*\\n" +
                                             "(((((?!,).)*,){3}((?!copy,(((?!,).)*,){8}\\7).)*\\n)*" +
                                             ".*editField,(((?!,).)*,){9}((?!(\\3\\9)).)*,(((?!,).)*,){3}\\n)";

    private static String chromeCopyBetweenEditRegex = "((((?!\"paste\").)*\\n)*)" +
                                                       "((.*\"paste\",(((?!,).)*,){8}(((?!,).)*),.*\\n)*)" +
                                                       "(((.*\\n)*)" +
                                                       "(.*\"editField\",(((?!,).)*,){8}(((?!,).)*),(((?!,).)*),.*\\n)" +
                                                       "(" +
                                                       "((((?!,).)*,){3}((?!\"copy\",(((?!,).)*,){8}\\16).)*\\n)*" +
                                                       ".*\"editField\",(((?!,).)*,){8}\\16(((?!(,|\\18)).)*),.*\\n))";

    private static String chromePasteBetweenEditRegex = "(.*\"editField\",(((?!,).)*,){8}(((?!,).)*,)(((?!,).)*),.*\\n)" +
                                                        "(" +
                                                        "((((?!,).)*,){3}\"paste\",(((?!,).)*,)(((?!,).)*),(((?!,).)*,){6}\\4.*\\n)*" +
                                                        ".*\"editField\",(((?!,).)*,){8}\\4(((\\6\\14|,).)*),.*\\n)";

    private static String chromeDoubleEditRegex = "(.*\"editField\",(((?!,).)*,){8}(((?!,).)*,)(((?!,).)*),.*\\n)" +
                                                  "(.*\"editField\",(((?!,).)*,){8}\\4.*\\n)";

    /**
     * Checks if the log contains a pattern that matches {@link WriteSimplifier#redundantEditCellRegex},
     * i.e the log contains two "editCell" actions with the same cell address,
     * and there is any number of action between them except "getCell" action
     * and "copyCell" action with the same cell address.
     *
     * @param   log the log that contains input actions.
     * @return  <code>true</code> if the log contains pattern that
     *          matches {@link WriteSimplifier#redundantEditCellRegex};
     *          <code>false</code> otherwise.
     */
    public static boolean containsRedundantEditCell(String log) {
        Pattern pattern = Pattern.compile(redundantEditCellRegex);
        Matcher matcher = pattern.matcher(log);

        return matcher.find();
    }

    /**
     * Checks if the log contains a pattern that matches {@link WriteSimplifier#redundantPasteIntoCellRegex},
     * i.e the log contains two "pasteIntoCell" actions with the same
     * workbook name, sheet name and target id (cell address) and
     * there is any number of action between them except "copyCell"
     * action with the same workbook name, sheet name and cell address.
     *
     * @param   log the log that contains input actions.
     * @return  <code>true</code> if the log contains pattern that
     *          matches {@link WriteSimplifier#redundantPasteIntoCellRegex};
     *          <code>false</code> otherwise.
     */
    public static boolean containsRedundantPasteIntoCell(String log) {
        Pattern p = Pattern.compile(redundantPasteIntoCellRegex);
        Matcher matcher = p.matcher(log);

        return matcher.find();
    }

    public static boolean containsRedundantChromePaste(String logs) {
        Pattern firstPattern = Pattern.compile(chromePasteRegex);
        Matcher firstMatcher = firstPattern.matcher(logs);

        Pattern secondPattern = Pattern.compile(chromeDoublePasteRegex);
        Matcher secondMatcher = secondPattern.matcher(logs);

        return secondMatcher.find();
    }

    /**
     * Removes all redundant "editCell" actions from the log.
     * <p>
     * If the log contains pattern that matches {@link WriteSimplifier#redundantEditCellRegex},
     * the method will remove first "editCell" action in the pattern. The method will be called
     * again if the log contains redundant "editCell" action after replacing the pattern that
     * matches {@link WriteSimplifier#redundantEditCellRegex} until there are none of them.
     * </p>
     *
     * @param log   the log that contains input actions.
     * @return      the log without redundant "editCell" actions.
     */
    public static String removeRedundantEditCell(String log) {
        /*
            $5 is a parameter of WriteSimplifier#redundantEditCellRegex that
            is responsible for every action after the first "editCell" action and
            the second "editCell" action in the pattern.
         */
        log = log.replaceAll(redundantEditCellRegex, "$5");

        if (containsRedundantEditCell(log)) {
            return removeRedundantEditCell(log);
        }

        return log;
    }

    /**
     * Removes all redundant "pasteIntoCell" actions from the log.
     * <p>
     * If the log contains pattern that matches {@link WriteSimplifier#redundantPasteIntoCellRegex},
     * the method will remove first "pasteIntoCell" action in the pattern. The method will be called
     * again if the log contains redundant "pasteIntoCell" action after replacing the pattern that
     * matches {@link WriteSimplifier#redundantPasteIntoCellRegex} until there are none of them.
     * </p>
     *
     * @param log   the log that contains input actions.
     * @return      the log without redundant "pasteIntoCell" actions.
     */
    public static String removeRedundantPasteIntoCell(String log) {
        /*
            $6 is a parameter of WriteSimplifier#redundantPasteIntoCellRegex that
            is responsible for every action after the first "pasteIntoCell" action
            and the second "pasteIntoCell" action in the pattern.
         */
        log = log.replaceAll(redundantPasteIntoCellRegex, "$6");

        if (containsRedundantPasteIntoCell(log)) {
            return removeRedundantPasteIntoCell(log);
        }

        return log;
    }

    public static String deleteRedundantChromePaste(String logs) {
        Pattern pattern = Pattern.compile(chromeDoublePasteRegex);
        Matcher matcher = pattern.matcher(logs);

//        String newLogs = logs.replaceAll(chromePasteRegex, "$11");
        logs = logs.replaceAll(chromeDoublePasteRegex, "$11");

        if (matcher.find()) {
            return deleteRedundantChromePaste(logs);
        }

        return logs;
    }

    public static boolean isRedundantChromeEditField(String logs) {
        Pattern editCopyPattern = Pattern.compile(chromeCopyBetweenEditRegex);
        Pattern editPastePattern = Pattern.compile(chromePasteBetweenEditRegex);

        return editCopyPattern.matcher(logs).find() &&
                !editPastePattern.matcher(logs).find();
    }

    public static String deleteRedundantChromeEditField(String logs) {
        Pattern patternCopy = Pattern.compile(chromeCopyBetweenEditRegex);
        Matcher matcherCopy = patternCopy.matcher(logs);

        Pattern patternPaste = Pattern.compile(chromePasteBetweenEditRegex);
        Matcher matcherPaste = patternPaste.matcher(logs);

        Pattern patternDouble = Pattern.compile(chromeDoubleEditRegex);
        Matcher matcherDouble = patternDouble.matcher(logs);

        if (matcherCopy.find() && !matcherPaste.find()) {

            logs = matcherCopy.replaceAll(mr -> {
                if (mr.group(8) != null &&
                        mr.group(16) != null &&
                        mr.group(8).equals(mr.group(16))) {
                    return mr.group(1) + mr.group(10);
                }
                return mr.group(1) + mr.group(11) + mr.group(20);
            });

            logs = logs.replaceAll(chromePasteBetweenEditRegex, "$8");
            return deleteRedundantChromeEditField(logs);
        }

        if (matcherDouble.find()) {
            logs = logs.replaceAll(chromeDoubleEditRegex, "$8");
            return deleteRedundantChromeEditField(logs);
        }

        return logs;
    }

    public static boolean isRedundantPasteIntoRange(String log) {
        String regex = ".*pasteIntoRange,(((?!,).)*,)((((?!,).)*,){3}(((?!,).)*,)).*\\n" +
                "(((((?!,).)*,){3}((?!copyRange,(((?!,).)*,){4}\\6).)*\\n)*" +
                ".*pasteIntoRange,(((?!,).)*,){4}\\6.*\\n)";

        Pattern p = Pattern.compile(regex);
        Matcher matcher = p.matcher(log);

        return matcher.find();
    }

    public static String deleteRedundantPasteIntoRange(String log) {
        String regex = ".*pasteIntoRange,(((?!,).)*,)((((?!,).)*,){3}(((?!,).)*,)).*\\n" +
                "(((((?!,).)*,){3}((?!copyRange,(((?!,).)*,){4}\\6).)*\\n)*" +
                ".*pasteIntoRange,(((?!,).)*,){4}\\6.*\\n)";

        Pattern p = Pattern.compile(regex);
        Matcher matcher = p.matcher(log);

        if (matcher.find()) {
            log = log.replaceAll(regex, "$8");
            return removeRedundantPasteIntoCell(log);
        }

        return log;
    }

}
