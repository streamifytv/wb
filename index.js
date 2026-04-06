const { Client, LocalAuth } = require('whatsapp-web.js');
const qrcode = require('qrcode-terminal');

// client setup
const client = new Client({
    authStrategy: new LocalAuth()
});

// QR code
client.on('qr', (qr) => {
    console.log('Scan this QR:');
    qrcode.generate(qr, { small: true });
});

// ready
client.on('ready', () => {
    console.log('✅ Bot is ready!');
});

// message handler
client.on('message', async (message) => {
    const text = message.body.toLowerCase();

    if (text === 'hi') {
        await message.reply('hello 👋');
    }
});

// error handling
client.on('disconnected', (reason) => {
    console.log('❌ Disconnected:', reason);
});

// start bot
client.initialize();
