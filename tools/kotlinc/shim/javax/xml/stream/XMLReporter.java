package javax.xml.stream;

public interface XMLReporter {
  void report(String message, String errorType, Object relatedInformation, Location location)
      throws XMLStreamException;
}
