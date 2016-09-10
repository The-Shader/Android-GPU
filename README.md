# Android-GPU

The repository contains the following:
- An image grayscale app, which uses OpenGL ES 3.1 along with a compute shader to manipulate images
- A camera preview app, which processes the camera as an external texture, rendered into a 
normal texture with a framebuffer, so that compute shaders like color quantizer or edgedetector can
operate on it (change the line in the source code where loadEdgeDetectorShader() is called to the other
shader loading functions)
