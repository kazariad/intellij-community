/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.refactoring.changeSignature;

import com.intellij.ide.actions.CopyReferenceAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.undo.BasicUndoableAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.command.undo.UndoableAction;
import com.intellij.openapi.command.undo.UnexpectedUndoException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.listeners.RefactoringElementAdapter;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.listeners.UndoRefactoringElementListener;
import com.intellij.refactoring.util.MoveRenameUsageInfo;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewUtil;
import com.intellij.util.CommonProcessors;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.hash.HashMap;
import com.intellij.util.containers.hash.HashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Maxim.Medvedev
 */
public abstract class ChangeSignatureProcessorBase extends BaseRefactoringProcessor {
  private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.changeSignature.ChangeSignatureProcessorBase");

  protected final ChangeInfo myChangeInfo;
  protected final PsiManager myManager;


  protected ChangeSignatureProcessorBase(Project project, ChangeInfo changeInfo) {
    super(project);
    myChangeInfo = changeInfo;
    myManager = PsiManager.getInstance(project);
  }

  protected ChangeSignatureProcessorBase(Project project, @Nullable Runnable prepareSuccessfulCallback, ChangeInfo changeInfo) {
    super(project, prepareSuccessfulCallback);
    myChangeInfo = changeInfo;
    myManager = PsiManager.getInstance(project);
  }

  @NotNull
  protected UsageInfo[] findUsages() {
    List<UsageInfo> infos = new ArrayList<UsageInfo>();

    final ChangeSignatureUsageProcessor[] processors = ChangeSignatureUsageProcessor.EP_NAME.getExtensions();
    for (ChangeSignatureUsageProcessor processor : processors) {
      ContainerUtil.addAll(infos, processor.findUsages(myChangeInfo));
    }
    infos = filterUsages(infos);
    return infos.toArray(new UsageInfo[infos.size()]);
  }

  protected List<UsageInfo> filterUsages(List<UsageInfo> infos) {
    Map<PsiElement, MoveRenameUsageInfo> moveRenameInfos = new HashMap<PsiElement, MoveRenameUsageInfo>();
    Set<PsiElement> usedElements = new HashSet<PsiElement>();

    List<UsageInfo> result = new ArrayList<UsageInfo>(infos.size() / 2);
    for (UsageInfo info : infos) {
      PsiElement element = info.getElement();
      if (info instanceof MoveRenameUsageInfo) {
        if (usedElements.contains(element)) continue;
        moveRenameInfos.put(element, (MoveRenameUsageInfo)info);
      }
      else {
        moveRenameInfos.remove(element);
        usedElements.add(element);
        if (!(info instanceof PossiblyIncorrectUsage) || ((PossiblyIncorrectUsage)info).isCorrect()) {
          result.add(info);
        }
      }
    }
    result.addAll(moveRenameInfos.values());
    return result;
  }


  @Override
  protected boolean isPreviewUsages(UsageInfo[] usages) {
    for (ChangeSignatureUsageProcessor processor : ChangeSignatureUsageProcessor.EP_NAME.getExtensions()) {
      if (processor.shouldPreviewUsages(myChangeInfo, usages)) return true;
    }
    return super.isPreviewUsages(usages);
  }

  protected void performRefactoring(UsageInfo[] usages) {
    final RefactoringElementListener elementListener = getTransaction().getElementListener(myChangeInfo.getMethod());
    final String fqn = CopyReferenceAction.elementToFqn(myChangeInfo.getMethod());
    if (fqn != null) {
      UndoableAction action = new BasicUndoableAction() {
        public void undo() throws UnexpectedUndoException {
          if (elementListener instanceof UndoRefactoringElementListener) {
            ((UndoRefactoringElementListener)elementListener).undoElementMovedOrRenamed(myChangeInfo.getMethod(), fqn);
          }
        }

        @Override
        public void redo() throws UnexpectedUndoException {
        }
      };
      UndoManager.getInstance(myProject).undoableActionPerformed(action);
    }
    try {
      final ChangeSignatureUsageProcessor[] processors = ChangeSignatureUsageProcessor.EP_NAME.getExtensions();

      for (UsageInfo usage : usages) {
        for (ChangeSignatureUsageProcessor processor : processors) {
          if (processor.processUsage(myChangeInfo, usage, true, usages)) break;
        }
      }

      LOG.assertTrue(myChangeInfo.getMethod().isValid());
      for (ChangeSignatureUsageProcessor processor : processors) {
        if (processor.processPrimaryMethod(myChangeInfo)) break;
      }

      for (UsageInfo usage : usages) {
        for (ChangeSignatureUsageProcessor processor : processors) {
          if (processor.processUsage(myChangeInfo, usage, false, usages)) break;
        }
      }

      final PsiElement method = myChangeInfo.getMethod();
      LOG.assertTrue(method.isValid());
      if (myChangeInfo.isNameChanged()) {
        elementListener.elementRenamed(method);
      }
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
    }
  }

  protected String getCommandName() {
    return RefactoringBundle.message("changing.signature.of.0", UsageViewUtil.getDescriptiveName(myChangeInfo.getMethod()));
  }

  public ChangeInfo getChangeInfo() {
    return myChangeInfo;
  }
}
