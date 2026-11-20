package com.thedirepack.constructioncalc.feature.conduitbending

import com.thedirepack.constructioncalc.core.ui.tool.ToolInputsViewModel

/** Holds the inputs so rotation keeps them, one instance per opened saved item. */
class ConduitBendingViewModel : ToolInputsViewModel<ConduitBendingInputs>(ConduitBendingInputs(), ConduitBendingInputs.serializer())
