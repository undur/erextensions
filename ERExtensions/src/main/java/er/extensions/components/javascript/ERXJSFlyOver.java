package er.extensions.components.javascript;

import org.apache.commons.lang3.StringUtils;

import com.webobjects.appserver.WOContext;

import er.extensions.components.ERXStatelessComponent;

public class ERXJSFlyOver extends ERXStatelessComponent {
	/**
	 * Do I need to update serialVersionUID?
	 * See section 5.6 <cite>Type Changes Affecting Serialization</cite> on page 51 of the 
	 * <a href="http://java.sun.com/j2se/1.4/pdf/serial-spec.pdf">Java Object Serialization Spec</a>
	 */
	private static final long serialVersionUID = 1L;

    protected String _linkId;
    protected String _spanId;

    public ERXJSFlyOver(WOContext context) {
        super(context);
    }

    @Override
    public void reset() {
        super.reset();
        _linkId = null;
        _spanId = null;
    }
   
    public String id() {
        return StringUtils.replace(context().elementID(), ".", "_");
    }
    
    public String alignString() {
    	return stringValueForBinding("align", "right");
    }
    
    public boolean needsClick() {
    	return booleanValueForBinding("needsClick", false);
    }

    public String linkId() {
        if(_linkId == null) {
            _linkId = "link_" + id();
        }
        return _linkId;
    }

    public String spanId() {
        if(_spanId == null) {
            _spanId = "span_" + id();
        }
        return _spanId;
    }

    public String toggleString() {
        return "ERXJSFlyOverCustomComponent_toggle(this, document.getElementById('"+spanId()+"')); return false;";
    }

    public String showString() {
        return "ERXJSFlyOverCustomComponent_show(this, document.getElementById('"+spanId()+"')); return false;";
    }

    public String hideAutoString() {
        return "ERXJSFlyOverCustomComponent_hideAuto(document.getElementById('"+spanId()+"')); return true;";
    }

    public String hideString() {
        return "ERXJSFlyOverCustomComponent_hide(document.getElementById('"+spanId()+"')); return true;";
    }

}