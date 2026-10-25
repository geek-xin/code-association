package com.geek.association.parse.messaging;

import com.geek.association.model.CodeIndex;
import com.geek.association.source.FileSource;

/** One middleware dialect. Adapters only describe contracts; propagation is shared. */
public interface MessagingAdapter {

    String type();

    void analyze(FileSource source, CodeIndex index, MessagingSupport support);
}
