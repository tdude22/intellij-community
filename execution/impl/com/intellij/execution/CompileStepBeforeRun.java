package com.intellij.execution;

import com.intellij.execution.configurations.ModuleRunProfile;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.remote.RemoteConfiguration;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.Semaphore;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * @author spleaner
 */
public class CompileStepBeforeRun implements StepsBeforeRunProvider {
  @NonNls protected static final String MAKE_PROJECT_ON_RUN_KEY = "makeProjectOnRun";

  private Project myProject;

  public CompileStepBeforeRun(@NotNull final Project project) {
    myProject = project;
  }

  public String getStepName() {
    return ExecutionBundle.message("before.launch.compile.step");
  }

  public boolean hasTask(final RunConfiguration configuration) {
    if (configuration instanceof ModuleRunProfile && !(configuration instanceof RemoteConfiguration)) {
      final ModuleRunProfile moduleRunConfiguration = (ModuleRunProfile)configuration;
      final Module[] modules = moduleRunConfiguration.getModules();
      if (modules != null && modules.length > 0 && getConfig().isCompileBeforeRunning(configuration)) {
        return true;
      }
    }

    return false;
  }

  private RunManagerConfig getConfig() {
    return RunManagerEx.getInstanceEx(myProject).getConfig();
  }

  public boolean executeTask(final DataContext context, final RunConfiguration configuration) {
    final ModuleRunProfile runConfiguration = (ModuleRunProfile)configuration;
    final Semaphore done = new Semaphore();
    final boolean[] result = new boolean[1];
    try {
      final CompileStatusNotification callback = new CompileStatusNotification() {
        public void finished(final boolean aborted, final int errors, final int warnings, CompileContext compileContext) {
          if (errors == 0 && !aborted) {
            result[0] = true;
          }

          done.up();
        }
      };

      ApplicationManager.getApplication().invokeAndWait(new Runnable() {
        public void run() {
          CompileScope scope;
          final CompilerManager compilerManager = CompilerManager.getInstance(myProject);
          if (Boolean.valueOf(System.getProperty(MAKE_PROJECT_ON_RUN_KEY, Boolean.FALSE.toString())).booleanValue()) {
            // user explicitly requested whole-project make
            scope = compilerManager.createProjectCompileScope(myProject);
          }
          else {
            scope = compilerManager.createModulesCompileScope(runConfiguration.getModules(), true);
          }

          done.down();
          compilerManager.make(scope, callback);
        }
      }, ModalityState.NON_MODAL);
    } catch (Exception e) {
      return false;
    }

    done.waitFor();
    return result[0];
  }

  public void copyTaskData(final RunConfiguration from, final RunConfiguration to) {
    // TODO: do we need this?
  }
}
