/* 
 * Java libusb wrapper
 *
 * http://libusbjava.sourceforge.net
 * This library is covered by the LGPL, read LGPL.txt for details.
 */
package ch.ntb.usb;

import java.io.IOException;

public class USBException extends IOException {

	public USBException(String string) {
		super(string);
	}

	/**
	 * 
	 */
	private static final long serialVersionUID = 1690857437804284710L;

}
