/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.cash.sqldelight.intellij.lang

import app.cash.sqldelight.core.SqlDelightFileIndex
import app.cash.sqldelight.core.SqlDelightProjectService
import app.cash.sqldelight.core.compiler.SqlDelightCompiler
import app.cash.sqldelight.core.lang.MigrationFile
import app.cash.sqldelight.core.lang.SqlDelightFile
import app.cash.sqldelight.core.lang.SqlDelightQueriesFile
import app.cash.sqldelight.intellij.util.GeneratedVirtualFile
import com.alecstrong.sql.psi.core.SqlAnnotationHolder
import com.alecstrong.sql.psi.core.psi.SqlAnnotatedElement
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.FileViewProviderFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.NonUrgentExecutor
import java.io.PrintStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

class SqlDelightFileViewProviderFactory : FileViewProviderFactory {
  override fun createFileViewProvider(
    file: VirtualFile,
    language: Language,
    manager: PsiManager,
    eventSystemEnabled: Boolean
  ): FileViewProvider {
    val module = SqlDelightProjectService.getInstance(manager.project).module(file)
      ?: return SingleRootFileViewProvider(manager, file, eventSystemEnabled)
    return SqlDelightFileViewProvider(manager, file, eventSystemEnabled, language, module)
  }
}

private class SqlDelightFileViewProvider(
  manager: PsiManager,
  virtualFile: VirtualFile,
  eventSystemEnabled: Boolean,
  private val language: Language,
  private val module: Module
) : SingleRootFileViewProvider(manager, virtualFile, eventSystemEnabled, language) {
  private val threadPool = Executors.newScheduledThreadPool(1)

  private val file: SqlDelightFile
    get() = getPsiInner(language) as SqlDelightFile

  private var filesGenerated = emptyList<VirtualFile>()
    set(value) {
      (field - value).forEach { it.delete(this) }
      field = value
    }

  private var condition = WriteCondition()

  override fun contentsSynchronized() {
    super.contentsSynchronized()

    if (!SqlDelightFileIndex.getInstance(module).isConfigured ||
      SqlDelightFileIndex.getInstance(module).sourceFolders(file).isEmpty()
    ) {
      return
    }

    condition.invalidated.set(true)

    if (ApplicationManager.getApplication().isUnitTestMode) {
      writeFiles(generateSqlDelightCode())
      return
    }

    val thisCondition = WriteCondition()
    condition = thisCondition
    threadPool.schedule(
      {
        ReadAction.nonBlocking(
          Callable<Map<String, StringBuilder>?> {
            try {
              generateSqlDelightCode()
            } catch (e: Throwable) {
              // IDE generating code should be best effort - source of truth is always the gradle
              // build, and its better to ignore the error and try again than crash and require
              // the IDE restarts.
              e.printStackTrace()
              null
            }
          }
        ).expireWhen(thisCondition)
          .finishOnUiThread(ModalityState.NON_MODAL, ::writeFiles)
          .submit(NonUrgentExecutor.getInstance())
      },
      1, TimeUnit.SECONDS
    )
  }

  /**
   * Attempt to generate the SQLDelight code for the file represented by the view provider.
   */
  private fun generateSqlDelightCode(): Map<String, StringBuilder> {
    if (module.isDisposed) return emptyMap()

    var shouldGenerate = true
    val annotationHolder = object : SqlAnnotationHolder {
      override fun createErrorAnnotation(element: PsiElement, s: String) {
        shouldGenerate = false
      }
    }

    // File is mutable so create a copy that wont be mutated.
    val file = file.copy() as SqlDelightFile

    shouldGenerate = try {
      PsiTreeUtil.processElements(file) { element ->
        when (element) {
          is PsiErrorElement -> return@processElements false
          is SqlAnnotatedElement -> element.annotate(annotationHolder)
        }
        return@processElements shouldGenerate
      }
    } catch (e: Throwable) {
      // If we encountered an exception while looking for errors, assume it was an error.
      false
    }

    if (shouldGenerate && !ApplicationManager.getApplication().isUnitTestMode) {
      val files = mutableMapOf<String, StringBuilder>()
      val fileAppender = { filePath: String -> StringBuilder().also { files[filePath] = it } }
      if (file is SqlDelightQueriesFile) {
        val dialect = SqlDelightProjectService.getInstance(module.project).dialect
        SqlDelightCompiler.writeInterfaces(module, dialect, file, fileAppender)
      } else if (file is MigrationFile) {
        SqlDelightCompiler.writeInterfaces(file, fileAppender)
      }

      return files
    } else {
      return emptyMap()
    }
  }

  private fun writeFiles(fileContent: Map<String, StringBuilder>?) {
    if (fileContent == null) return

    ApplicationManager.getApplication().runWriteAction {
      val files = mutableListOf<VirtualFile>()
      fileContent.forEach { (filePath, content) ->
        val vFile: VirtualFile by GeneratedVirtualFile(filePath, module)
        files.add(vFile)
        PrintStream(vFile.getOutputStream(this)).use {
          it.append(content)
        }
      }
      this.filesGenerated = files
    }
  }

  private class WriteCondition : BooleanSupplier {
    var invalidated = AtomicBoolean(false)

    override fun getAsBoolean() = invalidated.get()
  }
}
