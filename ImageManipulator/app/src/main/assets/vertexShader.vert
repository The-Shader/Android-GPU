attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 texCoord;

void main()
{
    texCoord = aTexCoord;
    gl_Position = vec4 ( aPosition.x, aPosition.y, 0.0, 1.0 );
}