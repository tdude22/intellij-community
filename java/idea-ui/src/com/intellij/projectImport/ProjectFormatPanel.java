// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.projectImport;

import com.intellij.ide.JavaUiBundle;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.components.StorageScheme;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class ProjectFormatPanel {
  private static final String STORAGE_FORMAT_PROPERTY = "default.storage.format";

  private JComboBox<StorageFormat> myStorageFormatCombo;
  private JPanel myWholePanel;

  public ProjectFormatPanel() {
    myStorageFormatCombo.insertItemAt(StorageFormat.DIR_BASED, 0);
    myStorageFormatCombo.insertItemAt(StorageFormat.FILE_BASED, 1);

    final PropertiesComponent instance = PropertiesComponent.getInstance();
    final String savedValue = instance.getValue(STORAGE_FORMAT_PROPERTY, StorageFormat.DIR_BASED.name());
    myStorageFormatCombo.setSelectedItem(StorageFormat.of(savedValue));

    final SimpleListCellRenderer<StorageFormat> renderer = SimpleListCellRenderer.create(StorageFormat.FILE_BASED.getTitle(),
                                                                                         LocalizationAware::getTitle);
    myStorageFormatCombo.setRenderer(renderer);
  }

  public JPanel getPanel() {
    return myWholePanel;
  }

  @NotNull
  public JComboBox<StorageFormat> getStorageFormatComboBox() {
    return myStorageFormatCombo;
  }

  public void updateData(@NotNull WizardContext context) {
    final StorageScheme format = isDefault() ? StorageScheme.DEFAULT : StorageScheme.DIRECTORY_BASED;
    context.setProjectStorageFormat(format);
    final StorageFormat storageFormat = StorageFormat.of(format);
    final PropertiesComponent instance = PropertiesComponent.getInstance();
    instance.setValue(STORAGE_FORMAT_PROPERTY, storageFormat.name(), StorageFormat.DIR_BASED.name());
  }

  public void setVisible(boolean visible) {
    myWholePanel.setVisible(visible);
  }

  public boolean isDefault() {
    final StorageFormat selectedItem = (StorageFormat)myStorageFormatCombo.getSelectedItem();
    return StorageFormat.isDefault(selectedItem);
  }

  private interface LocalizationAware {
    @NlsContexts.Label String getTitle();
  }

  public enum StorageFormat implements LocalizationAware {
    DIR_BASED{
      @Override public String getTitle(){
        return JavaUiBundle.message("label.directory.based", Project.DIRECTORY_STORE_FOLDER);
      }
    },
    FILE_BASED {
      @Override public String getTitle(){
        return JavaUiBundle.message("label.ipr.file.based");
      }
    };

    private static @NotNull StorageFormat of(@NotNull final String name) {
      // due to the uncertainty of what might have been saved in `PropertiesComponent`
      // the `StorageFormat::valueOf` method cannot be used here safely.
      if (DIR_BASED.name().equals(name)) return DIR_BASED;
      return FILE_BASED;
    }

    private static @NotNull StorageFormat of(@NotNull final StorageScheme id) {
      switch (id) {
        case DIRECTORY_BASED: return DIR_BASED;
        case DEFAULT: return FILE_BASED;
        default:
          throw new IllegalStateException("Unexpected value: " + id);
      }
    }

    public static boolean isDefault(StorageFormat storageFormat) {
      return FILE_BASED == storageFormat;
    }
  }
}