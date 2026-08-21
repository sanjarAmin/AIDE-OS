package javax.xml.stream;

public interface XMLResolver {
  Object resolveEntity(String publicID, String systemID, String baseURI, String namespace)
      throws XMLStreamException;
}
