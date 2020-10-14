/*
 * Copyright (C) NetStruxr, Inc. All rights reserved.
 *
 * This software is published under the terms of the NetStruxr
 * Public Software License version 0.5, a copy of which has been
 * included with this distribution in the LICENSE.NPL file.  */
package er.extensions.components;

import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver.WOContext;
import com.webobjects.foundation.NSData;

/**
 * Displays a byte array of data as a String.
 * 
 * @binding data
 */

public class ERXDisplayDataAsString extends WOComponent {
	/**
	 * Do I need to update serialVersionUID?
	 * See section 5.6 <cite>Type Changes Affecting Serialization</cite> on page 51 of the 
	 * <a href="http://java.sun.com/j2se/1.4/pdf/serial-spec.pdf">Java Object Serialization Spec</a>
	 */
	private static final long serialVersionUID = 1L;

    public ERXDisplayDataAsString(WOContext aContext) {
        super(aContext);
    }

    public String _string;
    @Override
    public boolean synchronizesVariablesWithBindings() { return false; }

    public String string() {
        if (_string==null) {
            NSData d=(NSData)valueForBinding("data");
            if (d!=null) _string=new String(d.bytes(0,d.length()));
        }
        return _string;
    }
    
}
