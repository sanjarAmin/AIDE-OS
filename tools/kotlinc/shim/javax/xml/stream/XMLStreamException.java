package javax.xml.stream;

public class XMLStreamException extends Exception {

  private static final long serialVersionUID = 2018819321811497362L;

  protected Throwable nested;
  protected Location location;

  public XMLStreamException() {
    super();
  }

  public XMLStreamException(String msg) {
    super(msg);
  }

  public XMLStreamException(Throwable th) {
    super(th);
    nested = th;
  }

  public XMLStreamException(String msg, Throwable th) {
    super(msg, th);
    nested = th;
  }

  public XMLStreamException(String msg, Location location, Throwable th) {
    super("ParseError at [row,col]:[" + location.getLineNumber() + "," + location.getColumnNumber()
        + "]\nMessage: " + msg);
    nested = th;
    this.location = location;
  }

  public XMLStreamException(String msg, Location location) {
    super("ParseError at [row,col]:[" + location.getLineNumber() + "," + location.getColumnNumber()
        + "]\nMessage: " + msg);
    this.location = location;
  }

  public Throwable getNestedException() {
    return nested;
  }

  public Location getLocation() {
    return location;
  }
}
