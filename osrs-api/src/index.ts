import express, { Request, Response } from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import axios from 'axios';
import { QuestTool, Duradel, Nieve, KonarQuoMaten } from 'osrs-tools';
// @ts-ignore
import hiscores from 'osrs-json-hiscores';

dotenv.config();

const app = express();
const port = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

const USER_AGENT = 'BobBot OSRS API - @yourdiscord';

// --- Hiscores ---
app.get('/api/player/:username', async (req: Request, res: Response) => {
    try {
        const { username } = req.params;
        const stats = await hiscores.getStats(username);
        res.json(stats);
    } catch (error: any) {
        if (error.message && error.message.includes('404')) {
            res.status(404).json({ error: 'Player not found' });
        } else {
            res.status(500).json({ error: error.message });
        }
    }
});

// --- Items & Prices ---
// Using OSRS Wiki Prices API directly as in the previous Java version
app.get('/api/item/:query', async (req: Request, res: Response) => {
    try {
        const { query } = req.params;
        const mappingRes = await axios.get('https://prices.runescape.wiki/api/v1/osrs/mapping', {
            headers: { 'User-Agent': USER_AGENT }
        });
        const mapping = mappingRes.data;

        const item = mapping.find((i: any) => i.name.toLowerCase() === query.toLowerCase()) 
                  || mapping.find((i: any) => i.name.toLowerCase().startsWith(query.toLowerCase()));

        if (!item) return res.status(404).json({ error: 'Item not found' });

        const priceRes = await axios.get(`https://prices.runescape.wiki/api/v1/osrs/latest?id=${item.id}`, {
            headers: { 'User-Agent': USER_AGENT }
        });
        
        res.json({
            id: item.id,
            name: item.name,
            prices: priceRes.data.data[item.id]
        });
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

app.get('/api/items/search/:query', async (req: Request, res: Response) => {
    try {
        const { query } = req.params;
        const limit = parseInt(req.query.limit as string) || 10;
        const mappingRes = await axios.get('https://prices.runescape.wiki/api/v1/osrs/mapping', {
            headers: { 'User-Agent': USER_AGENT }
        });
        const mapping = mappingRes.data;
        const results = mapping
            .filter((i: any) => i.name.toLowerCase().includes(query.toLowerCase()))
            .sort((a: any, b: any) => a.name.length - b.name.length)
            .slice(0, limit);
        res.json(results);
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

// --- Wiki ---
app.get('/api/wiki/:title', async (req: Request, res: Response) => {
    try {
        const { title } = req.params;
        const url = `https://oldschool.runescape.wiki/api.php?action=query&prop=extracts&exintro&explaintext&format=json&titles=${encodeURIComponent(title)}`;
        const response = await axios.get(url, {
            headers: { 'User-Agent': USER_AGENT }
        });
        
        const pages = response.data.query.pages;
        const pageId = Object.keys(pages)[0];
        const extract = pages[pageId].extract;

        res.json({
            title,
            url: `https://oldschool.runescape.wiki/w/${encodeURIComponent(title).replace(/%20/g, '_')}`,
            summary: extract ? extract.split('\n')[0] : null
        });
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

// --- Quests (New) ---
app.get('/api/quests/:name', (req: Request, res: Response) => {
    try {
        const { name } = req.params;
        const quest = QuestTool.getQuestByName(name);
        if (!quest) {
            return res.status(404).json({ error: 'Quest not found' });
        }
        res.json(quest);
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

// --- Slayer (New) ---
app.get('/api/slayer/:master', (req: Request, res: Response) => {
    try {
        const { master } = req.params;
        let tasks;
        switch (master.toLowerCase()) {
            case 'duradel': tasks = Duradel.tasks; break;
            case 'nieve': tasks = Nieve.tasks; break;
            case 'konar': tasks = KonarQuoMaten.tasks; break;
            default:
                return res.status(404).json({ error: 'Slayer master not found' });
        }
        res.json(tasks);
    } catch (error: any) {
        res.status(500).json({ error: error.message });
    }
});

app.listen(port, () => {
    console.log(`OSRS API listening at http://localhost:${port}`);
});
