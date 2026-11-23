package io.github.joshitup.lantern.update

import java.io.IOException

/** GitHub answered, but not with a usable release response (non-2xx other than "no releases", or a body that isn't release JSON). */
class UpdateCheckException(message: String) : IOException(message)
