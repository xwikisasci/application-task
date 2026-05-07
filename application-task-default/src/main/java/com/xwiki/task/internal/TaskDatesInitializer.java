/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xwiki.task.internal;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.suigeneris.jrcs.diff.DifferentiationFailedException;
import org.suigeneris.jrcs.diff.delta.AddDelta;
import org.suigeneris.jrcs.diff.delta.Chunk;
import org.suigeneris.jrcs.diff.delta.Delta;
import org.suigeneris.jrcs.rcs.Version;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.MacroBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.block.match.MacroBlockMatcher;
import org.xwiki.rendering.macro.MacroExecutionException;
import org.xwiki.rendering.syntax.Syntax;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.DocumentRevisionProvider;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.doc.rcs.XWikiRCSNodeInfo;
import com.xwiki.date.DateMacroConfiguration;
import com.xwiki.task.MacroUtils;
import com.xwiki.task.TaskException;
import com.xwiki.task.model.Task;

/**
 * Class that will handle the initialization of the task creation and completion date if they are missing.
 *
 * @version $Id$
 * @since 3.0
 */
@Component(roles = TaskDatesInitializer.class)
@Singleton
public class TaskDatesInitializer
{
    private static final String TASK_PATTERN_STRING = "\\{\\{task[^}]*/?}}";

    private static final String TASK_PARAMETERS_PATTERN_STRING = "(\\w+)=['\"]([^'\"]*)['\"]";

    private final Pattern taskPattern = Pattern.compile(TASK_PATTERN_STRING);

    private final Pattern paramPattern = Pattern.compile(TASK_PARAMETERS_PATTERN_STRING);

    @Inject
    private DateMacroConfiguration configuration;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private DocumentRevisionProvider revisionProvider;

    @Inject
    private Logger logger;

    @Inject
    private MacroBlockFinder blockFinder;

    @Inject
    private MacroUtils macroUtils;

    /**
     * @param content the parsed content of the document.
     * @return true if the XDOM contains task macros with incomplete data. False otherwise.
     */
    public boolean doesDocumentContainIncompleteTasks(XDOM content)
    {
        List<Block> taskMacros =
            content.getBlocks(new MacroBlockMatcher(Task.MACRO_NAME), Block.Axes.DESCENDANT_OR_SELF);
        return taskMacros.stream().anyMatch(
            macro -> macro.getParameter(Task.REPORTER) == null || "".equals(macro.getParameter(Task.REPORTER)));
    }

    /**
     * @param ownerDoc the document that contains tasks which are missing the create date and/or the complete date.
     * @param content the parsed content of the document.
     * @param context the current context.
     * @return true if the xdom has been changed, false otherwise.
     * @throws TaskException if the extraction failed
     */
    public boolean processDocument(XWikiDocument ownerDoc, XDOM content, XWikiContext context) throws TaskException
    {
        Version[] versions;
        List<MacroBlock> updatableMacros = new ArrayList<>();
        Map<String, Block> createDateTasks = new HashMap<>();
        Map<String, Block> completeDateTasks = new HashMap<>();
        XDOM updatedContent = content;
        try {
            updatedContent = blockFinder.iterativeFind(content, ownerDoc.getSyntax(), true, macroBlock -> {
                updatableMacros.add(macroBlock);
                if (Task.MACRO_NAME.equals(macroBlock.getId())) {
                    separateProcessableTasks(macroBlock, createDateTasks, completeDateTasks);
                }
                return MacroBlockFinder.Lookup.CONTINUE;
            });

            versions = ownerDoc.getRevisions(context);
        } catch (XWikiException | MacroExecutionException e) {
            throw new TaskException(
                String.format("Could not extract the tasks from the content of [%s].", ownerDoc.getDocumentReference()),
                e);
        }
        SimpleDateFormat storageFormat = new SimpleDateFormat(configuration.getStorageDateFormat());

        if (createDateTasks.isEmpty() && completeDateTasks.isEmpty()) {
            return false;
        }
        int foundTasks = createDateTasks.size() + completeDateTasks.size();
        // Check also for the first version before comparing the versions.
        extractFromVersion1OfDoc(versions, createDateTasks, completeDateTasks, ownerDoc, storageFormat);

        compareVersionsAndExtract(context, versions, createDateTasks, completeDateTasks, ownerDoc, storageFormat);

        ListIterator<MacroBlock> li = updatableMacros.listIterator(updatableMacros.size());
        while (li.hasPrevious()) {
            MacroBlock macroBlock = li.previous();
            try {
                if (macroBlock.getChildren() != null && !macroBlock.getChildren().isEmpty()) {
                    macroUtils.updateMacroContent(macroBlock,
                        macroUtils.renderMacroContent(macroBlock.getChildren(), Syntax.XWIKI_2_1));
                }
            } catch (Exception e) {
                logger.warn(
                    "Failed to render the new content of the  macro [{}] from page [{}]. This might lead to incomplete "
                        + "tasks still being present in the page.", macroBlock.getId(),
                    ownerDoc.getDocumentReference());
            }
        }
        // If tasks were found but they are still in the map, that means they weren't initialised properly.
        return foundTasks != createDateTasks.size() + completeDateTasks.size();
    }

    private void compareVersionsAndExtract(XWikiContext context, Version[] versions, Map<String, Block> createDateTasks,
        Map<String, Block> completeDateTasks, XWikiDocument ownerDoc, SimpleDateFormat storageFormat)
    {
        // We start comparing from the last/current version. We compare (n - 1)-th to the n-th version and slide to
        // the first two versions. Ex: versions array length n = 5; step1: cmp(3, 4) -> fails; step2: cmp(2, 4) ->
        // succeeds; step3: cmp(1, 2) -> succeeds; step4: cmp(0, 1).
        int j = versions.length - 1;
        for (int i = versions.length - 2; i >= 0; i--) {
            if (createDateTasks.isEmpty() && completeDateTasks.isEmpty()) {
                break;
            }
            try {
                List<Delta> deltas =
                    ownerDoc.getContentDiff(versions[i].toString(), versions[j].toString(), context);
                XWikiRCSNodeInfo versionInfo = ownerDoc.getRevisionInfo(versions[j].toString(), context);
                for (Delta delta : deltas) {
                    inferDateFromContent(createDateTasks, completeDateTasks, storageFormat, versionInfo.getDate(),
                        versionInfo.getAuthor(), delta);
                }
                j = i;
            } catch (XWikiException | DifferentiationFailedException e) {
                logger.warn("Failed to compare the versions [{}] and [{}] of the document [{}]. Cause [{}].",
                    versions[j], versions[i], ownerDoc.getDocumentReference(), ExceptionUtils.getRootCauseMessage(e));
            }
        }
    }

    private void extractFromVersion1OfDoc(Version[] versions, Map<String, Block> createDateTasks,
        Map<String, Block> completeDateTasks, XWikiDocument ownerDoc, SimpleDateFormat storageFormat)
    {
        try {
            XWikiDocument v1Doc = revisionProvider.getRevision(ownerDoc, versions[0].toString());
            String author = serializer.serialize(v1Doc.getAuthorReference());
            inferDateFromContent(createDateTasks, completeDateTasks, storageFormat, v1Doc.getDate(), author,
                new AddDelta(0, new Chunk(new String[] { v1Doc.getContent() }, 0, 1)));
        } catch (XWikiException e) {
            logger.warn(
                "Failed to infer the completeDate/createDate of the tasks from the version [{}] of the document [{}].",
                versions[0].toString(), ownerDoc.getDocumentReference());
        }
    }

    private void inferDateFromContent(Map<String, Block> createDateBlocks, Map<String, Block> completeDateBlocks,
        SimpleDateFormat storageFormat, Date date, String author, Delta delta)
    {
        Matcher matcher = taskPattern.matcher(delta.getRevised().toString());
        Matcher originalTasksMatcher = taskPattern.matcher(delta.getOriginal().toString());
        Set<String> originalTasks = new HashSet<>();
        while (originalTasksMatcher.find()) {
            String task = originalTasksMatcher.group();
            Matcher paramMatcher = paramPattern.matcher(task);
            String taskReference = "";
            while (paramMatcher.find()) {
                if (paramMatcher.group(1).equals(Task.REFERENCE)) {
                    taskReference = paramMatcher.group(2);
                }
            }
            if (!taskReference.isEmpty()) {
                originalTasks.add(taskReference);
            }
        }
        while (matcher.find()) {
            String task = matcher.group();
            Matcher paramMatcher = paramPattern.matcher(task);
            String taskReference = "";
            String status = "";
            while (paramMatcher.find()) {
                if (paramMatcher.group(1).equals(Task.REFERENCE)) {
                    taskReference = paramMatcher.group(2);
                } else if (paramMatcher.group(1).equals(Task.STATUS)) {
                    status = paramMatcher.group(2);
                }
            }
            if (delta instanceof AddDelta || !originalTasks.contains(taskReference)) {
                if (createDateBlocks.containsKey(taskReference)) {
                    createDateBlocks.get(taskReference).setParameter(Task.REPORTER, author);
                }
                setParameterAndRemoveIfPresent(createDateBlocks, Task.CREATE_DATE, storageFormat.format(date),
                    taskReference);
            }
            if (Task.STATUS_DONE.equals(status)) {
                setParameterAndRemoveIfPresent(completeDateBlocks, Task.COMPLETE_DATE, storageFormat.format(date),
                    taskReference);
            }
        }
    }

    private void setParameterAndRemoveIfPresent(Map<String, Block> map, String paramKey, String paramVal,
        String reference)
    {
        if (map.containsKey(reference)) {
            map.get(reference).setParameter(paramKey, paramVal);
            map.remove(reference);
        }
    }

    private void separateProcessableTasks(Block macroBlock, Map<String, Block> noCreateDateTasks,
        Map<String, Block> noCompleteDateTasks)
    {
        String reference = macroBlock.getParameter(Task.REFERENCE);
        if (macroBlock.getParameter(Task.CREATE_DATE) == null) {
            noCreateDateTasks.put(reference, macroBlock);
        }
        if (macroBlock.getParameter(Task.COMPLETE_DATE) == null
            && Task.STATUS_DONE.equals(macroBlock.getParameter(Task.STATUS)))
        {
            noCompleteDateTasks.put(reference, macroBlock);
        }
    }
}
