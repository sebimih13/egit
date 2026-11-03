
package org.eclipse.egit.ui.amiq;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

/**
 * Message Dialog with a copyable message area
 */
public class MessageDialogWithCopy extends Dialog {

	private final String dialogTitle;

	private final String dialogMessage;

	private MessageDialogWithCopy(Shell parentShell, String dialogTitle,
			String dialogMessage) {
		super(parentShell);
		this.dialogTitle = dialogTitle;
		this.dialogMessage = dialogMessage;
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(dialogTitle);
		shell.setSize(new Point(1100, 700));
		shell.setLocation(600, 300);
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = new Composite(parent, SWT.NONE);
		GridLayout areaGridData = new GridLayout(1, false);
		areaGridData.marginWidth = 0;
		areaGridData.marginHeight = 0;
		area.setLayout(areaGridData);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(area);

		Browser browser = new Browser(area, SWT.NONE);
		browser.setText("<pre>" + dialogMessage + "</pre>"); //$NON-NLS-1$ //$NON-NLS-2$
		GridDataFactory.fillDefaults().grab(true, true).applyTo(browser);

		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL,
				true);
		createButton(parent, IDialogConstants.CANCEL_ID,
				IDialogConstants.CANCEL_LABEL, false);
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	/**
	 * Open a dialog with a selectable message area.
	 *
	 * @param parent
	 *            parent shell
	 * @param title
	 *            dialog title
	 * @param message
	 *            message to display
	 */
	public static void open(Shell parent, String title, String message) {
		MessageDialogWithCopy dialog = new MessageDialogWithCopy(parent, title,
				message);
		dialog.open();
	}

}
