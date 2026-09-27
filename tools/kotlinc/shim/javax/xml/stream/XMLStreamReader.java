package javax.xml.stream;

import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;

public interface XMLStreamReader extends XMLStreamConstants {

  Object getProperty(String name) throws IllegalArgumentException;

  int next() throws XMLStreamException;

  void require(int type, String namespaceURI, String localName) throws XMLStreamException;

  String getElementText() throws XMLStreamException;

  int nextTag() throws XMLStreamException;

  boolean hasNext() throws XMLStreamException;

  void close() throws XMLStreamException;

  String getNamespaceURI(String prefix);

  boolean isStartElement();

  boolean isEndElement();

  boolean isCharacters();

  boolean isWhiteSpace();

  String getAttributeValue(String namespaceURI, String localName);

  int getAttributeCount();

  QName getAttributeName(int index);

  String getAttributeNamespace(int index);

  String getAttributeLocalName(int index);

  String getAttributePrefix(int index);

  String getAttributeType(int index);

  String getAttributeValue(int index);

  boolean isAttributeSpecified(int index);

  int getNamespaceCount();

  String getNamespacePrefix(int index);

  String getNamespaceURI(int index);

  NamespaceContext getNamespaceContext();

  int getEventType();

  String getText();

  char[] getTextCharacters();

  int getTextCharacters(int sourceStart, char[] target, int targetStart, int length)
      throws XMLStreamException;

  int getTextStart();

  int getTextLength();

  String getEncoding();

  boolean hasText();

  Location getLocation();

  QName getName();

  String getLocalName();

  boolean hasName();

  String getNamespaceURI();

  String getPrefix();

  String getVersion();

  boolean isStandalone();

  boolean standaloneSet();

  String getCharacterEncodingScheme();

  String getPITarget();

  String getPIData();
}
