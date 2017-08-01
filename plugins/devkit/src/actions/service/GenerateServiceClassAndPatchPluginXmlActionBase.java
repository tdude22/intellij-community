/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package org.jetbrains.idea.devkit.actions.service;

import com.intellij.CommonBundle;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.IdeView;
import com.intellij.ide.actions.CreateInDirectoryActionBase;
import com.intellij.ide.actions.ElementCreator;
import com.intellij.ide.fileTemplates.JavaTemplateUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.application.RunResult;
import com.intellij.openapi.application.WriteActionAware;
import com.intellij.openapi.command.UndoConfirmationPolicy;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.ui.DocumentAdapter;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.xml.DomFileElement;
import com.intellij.util.xml.DomManager;
import com.intellij.xml.util.IncludedXmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.devkit.DevKitBundle;
import org.jetbrains.idea.devkit.actions.DevkitActionsUtil;
import org.jetbrains.idea.devkit.dom.Extensions;
import org.jetbrains.idea.devkit.dom.IdeaPlugin;
import org.jetbrains.idea.devkit.util.DescriptorUtil;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

public abstract class GenerateServiceClassAndPatchPluginXmlActionBase extends CreateInDirectoryActionBase implements WriteActionAware {
  private final Set<XmlFile> myFilesToPatch = new HashSet<>();

  public GenerateServiceClassAndPatchPluginXmlActionBase(String text, String description) {
    super(text, description, null);
  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }


  private void patchPluginXmls(@NotNull XmlFile[] pluginXmls, @Nullable PsiClass serviceInterface,
                               @NotNull PsiClass serviceImplementation) {
    DescriptorUtil.checkPluginXmlsWritable(serviceImplementation.getProject(), pluginXmls);
    for (XmlFile pluginXml : pluginXmls) {
      patchPluginXml(pluginXml, serviceInterface, serviceImplementation);
    }
  }

  private void patchPluginXml(XmlFile pluginXml, @Nullable PsiClass serviceInterface, @NotNull PsiClass serviceImplementation) {
    DomFileElement<IdeaPlugin> fileElement = DomManager.getDomManager(pluginXml.getProject()).getFileElement(pluginXml, IdeaPlugin.class);
    if (fileElement == null) {
      throw new IncorrectOperationException(DevKitBundle.message("error.cannot.process.plugin.xml", pluginXml));
    }

    IdeaPlugin ideaPlugin = fileElement.getRootElement();
    Extensions targetExtensions = ideaPlugin.getExtensions().stream()
      .filter(extensions -> !(extensions instanceof IncludedXmlTag))
      .filter(extensions -> Extensions.DEFAULT_PREFIX.equals(extensions.getDefaultExtensionNs().getStringValue()))
      .findAny()
      .orElseGet(() -> ideaPlugin.addExtensions());

    XmlTag serviceTag = targetExtensions.addExtension(Extensions.DEFAULT_PREFIX + "." + getTagName()).getXmlTag();
    if (serviceInterface != null) {
      serviceTag.setAttribute("serviceInterface", serviceInterface.getQualifiedName());
    }
    serviceTag.setAttribute("serviceImplementation", serviceImplementation.getQualifiedName());
  }

  @Override
  public final void actionPerformed(final AnActionEvent e) {
    IdeView view = e.getData(LangDataKeys.IDE_VIEW);
    if (view == null) {
      return;
    }

    Project project = e.getProject();

    PsiDirectory dir = view.getOrChooseDirectory();
    if (dir == null) return;

    ServiceCreator serviceCreator = new ServiceCreator(dir, getInterfaceTemplateName(), getOnlyImplementationTemplateName());
    PsiClass[] createdClasses = invokeDialog(project, serviceCreator);
    if (createdClasses == null) {
      return;
    }

    for (PsiClass createdClass : createdClasses) {
      view.selectElement(createdClass);
    }
  }

  @Nullable
  private PsiClass[] invokeDialog(Project project, ServiceCreator serviceCreator) {
    DialogWrapper dialog = new NewServiceDialog(project, serviceCreator);
    dialog.show();
    return serviceCreator.getCreatedClasses();
  }

  protected abstract String getTagName();

  protected abstract String getOnlyImplementationTemplateName();
  protected abstract String getInterfaceTemplateName();

  protected abstract String getDialogTitle();


  private class NewServiceDialog extends DialogWrapper {
    private final ServiceCreator myServiceCreator;

    private JPanel myTopPanel;

    private JTextField myServiceNameTextField;
    private JCheckBox mySeparateServiceInterfaceCheckbox;
    private JTextField myServiceImplementationTextField;
    private JLabel myServiceNameLabel;

    private boolean myAdjusting = false;
    private boolean myNeedAdjust = true;

    NewServiceDialog(@Nullable Project project, ServiceCreator serviceCreator) {
      super(project);

      setOKActionEnabled(false);
      setTitle(getDialogTitle());

      myServiceCreator = serviceCreator;

      mySeparateServiceInterfaceCheckbox.addActionListener(e -> {
        if (mySeparateServiceInterfaceCheckbox.isSelected()) {
          myServiceImplementationTextField.setEnabled(true);
          myServiceNameLabel.setText(DevKitBundle.message("new.service.dialog.interface"));
        } else {
          myServiceImplementationTextField.setEnabled(false);
          myServiceNameLabel.setText(DevKitBundle.message("new.service.dialog.class"));
        }
        adjustServiceImplementationTextField();
      });

      myServiceNameTextField.getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(DocumentEvent e) {
          setOKActionEnabled(myServiceNameTextField.getText().length() > 0);
          adjustServiceImplementationTextField();
        }
      });
      myServiceImplementationTextField.getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(DocumentEvent e) {
          if (!myAdjusting) {
            myNeedAdjust = false;
          }
        }
      });

      init();
    }

    private void adjustServiceImplementationTextField() {
      if (!mySeparateServiceInterfaceCheckbox.isSelected()) {
        myAdjusting = true;
        myServiceImplementationTextField.setText("");
        myAdjusting = false;
      } else if (myNeedAdjust) {
        myAdjusting = true;
        myServiceImplementationTextField.setText("impl." + myServiceNameTextField.getText() + "Impl");
        myAdjusting = false;
      }
    }

    @Nullable
    @Override
    public JComponent getPreferredFocusedComponent() {
      return myServiceNameTextField;
    }

    @Override
    protected void doOKAction() {
      if (mySeparateServiceInterfaceCheckbox.isSelected()) {
        // separated interface and implementation
        String serviceInterface = myServiceNameTextField.getText().trim();
        String serviceImplementation = myServiceImplementationTextField.getText().trim();

        if (checkInput(serviceInterface) && checkInput(serviceImplementation) &&
            myServiceCreator.createInterfaceAndImplementation(serviceInterface, serviceImplementation)) {
          close(OK_EXIT_CODE);
        }
      } else {
        // only implementation
        String serviceOnlyImplementation = myServiceNameTextField.getText().trim();

        if (checkInput(serviceOnlyImplementation) && myServiceCreator.createOnlyImplementation(serviceOnlyImplementation)) {
          close(OK_EXIT_CODE);
        }
      }
    }

    private boolean checkInput(String input) {
      if (StringUtil.isEmpty(input)) {
        Messages.showMessageDialog(getContentPane(), IdeBundle.message("error.name.should.be.specified"),
                                   CommonBundle.getErrorTitle(), Messages.getErrorIcon());
        return false;
      }
      return true;
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
      return myTopPanel;
    }
  }


  private class ServiceCreator {
    private final Logger LOG = Logger.getInstance("#" + ServiceCreator.class.getCanonicalName());

    private final PsiDirectory myDirectory;
    private final String myServiceInterfaceTemplateName;
    private final String myServiceOnlyImplementationTemplateName;

    private PsiClass[] createdClasses = null;

    ServiceCreator(PsiDirectory directory,
                           String serviceInterfaceTemplateName,
                           String serviceOnlyImplementationTemplateName) {
      myDirectory = directory;
      this.myServiceInterfaceTemplateName = serviceInterfaceTemplateName;
      this.myServiceOnlyImplementationTemplateName = serviceOnlyImplementationTemplateName;
    }

    PsiClass[] getCreatedClasses() {
      return createdClasses;
    }

    /**
     * @return whether the service was created (which indicates whether the create service dialog can be closed).
     */
    @SuppressWarnings("ConstantConditions") // no NPE here since created classes are not anonymous
    boolean createInterfaceAndImplementation(String interfaceName, String implementationName) {
      return doCreateService(() -> {
        PsiClass createdInterface = DevkitActionsUtil.createSinglePluginClass(
          interfaceName, myServiceInterfaceTemplateName, myDirectory, myFilesToPatch, getTemplatePresentation())[0];
        if (createdInterface == null) {
          return false; // canceled
        }

        PsiClass createdImplementation = DevkitActionsUtil.createSinglePluginClass(
          implementationName, JavaTemplateUtil.INTERNAL_CLASS_TEMPLATE_NAME, myDirectory, myFilesToPatch, getTemplatePresentation())[0];
        if (createdImplementation == null) {
          // unlikely to happen since plugin.xml to patch is already defined after service interface is created
          return false;
        }

        JavaPsiFacade facade = JavaPsiFacade.getInstance(myDirectory.getProject());
        PsiElementFactory factory = facade.getElementFactory();
        PsiJavaCodeReferenceElement interfaceReference =
          factory.createReferenceElementByFQClassName(createdInterface.getQualifiedName(), createdImplementation.getResolveScope());
        createdImplementation.getImplementsList().add(interfaceReference);

        handleCreatedElements(createdInterface, createdImplementation);
        createdClasses = new PsiClass[]{createdInterface, createdImplementation};
        return true;
      });
    }

    /**
     * @return whether the service was created (which indicates whether the create service dialog can be closed).
     */
    boolean createOnlyImplementation(String onlyImplementationName) {
      return doCreateService(() -> {
        PsiClass createOnlyImplementation = DevkitActionsUtil.createSinglePluginClass(
          onlyImplementationName, myServiceOnlyImplementationTemplateName, myDirectory, myFilesToPatch, getTemplatePresentation())[0];
        if (createOnlyImplementation == null) {
          return false; // canceled
        }

        handleCreatedElements(null, createOnlyImplementation);
        createdClasses = new PsiClass[]{createOnlyImplementation};
        return true;
      });
    }

    private boolean doCreateService(Callable<Boolean> action) {
      RunResult<Boolean> result = new WriteCommandAction<Boolean>(getProject(), DevKitBundle.message("new.service.class.action.name")) {
        @Override
        protected void run(@NotNull Result<Boolean> result) throws Throwable {
          result.setResult(action.call());
        }

        @Override
        protected UndoConfirmationPolicy getUndoConfirmationPolicy() {
          return UndoConfirmationPolicy.REQUEST_CONFIRMATION;
        }
      }.execute();

      if (result.hasException()) {
        handleException(result.getThrowable());
        return false;
      }

      return result.getResultObject();
    }

    private void handleCreatedElements(@Nullable PsiClass createdInterface, @NotNull PsiClass createdImplementation) {
      XmlFile[] pluginXmls = myFilesToPatch.toArray(new XmlFile[myFilesToPatch.size()]);
      patchPluginXmls(pluginXmls, createdInterface, createdImplementation);
    }

    private void handleException(Throwable t) {
      LOG.info(t);
      String errorMessage = ElementCreator.getErrorMessage(t);
      Messages.showMessageDialog(
        getProject(), errorMessage, DevKitBundle.message("error.cannot.create.service.class"), Messages.getErrorIcon());
    }

    private Project getProject() {
      return myDirectory.getProject();
    }
  }
}
